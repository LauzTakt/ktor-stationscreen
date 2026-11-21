// KTVM firmware: receives Kotlin program images over USB serial and runs them.
//
// Protocol (host -> device):  "\x7fKTV" u32 len  u32 crc32  then len bytes in 512-byte chunks;
// the device answers "[ktvm] ready" after the header, 0x06 after every chunk, and finally
// "[ktvm] upload ok (N bytes)". "\x7fKTR" re-runs the stored program, "\x7fKTS" stops it.
// "\x7fKTC" u16 len "key=value" sets a device setting ("key=" removes it, len 0 just reads them); the device
// answers "[ktvm] config: k1=v1;k2=v2" and restarts the program so it sees the new value.
#include <Arduino.h>
#include <LittleFS.h>
#include <SPI.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <esp_heap_caps.h>
#include <esp_timer.h>

#include "hal.h"
#include "ktvm.h"
#include "net.h"
#include "display.h"

#ifndef KTVM_BAUD
#define KTVM_BAUD 460800
#endif
#define PROG_PATH "/prog.ktv"
#define CONFIG_PATH "/config.txt"
#define MAX_IMAGE (160 * 1024)
#define VM_STACK_BYTES (40 * 1024)
#ifndef KTVM_MAX_HEAP
#define KTVM_MAX_HEAP (80 * 1024)
#endif

enum { VM_IDLE, VM_RUNNING };
static volatile int g_state = VM_IDLE;
static uint8_t* volatile g_pending = nullptr;
static volatile size_t g_pending_len = 0;
static volatile bool g_rerun = false;
static bool g_fs_ok = false;

// ------------------------------------------------------------------ HAL for the VM
void hal_write(const char* s, size_t n) { Serial.write((const uint8_t*)s, n); }
uint32_t hal_millis() { return millis(); }
uint32_t hal_micros() { return micros(); }
uint64_t hal_micros64() { return (uint64_t)esp_timer_get_time(); }
bool hal_delay(uint32_t ms) {
  uint32_t start = millis();
  while (millis() - start < ms) {
    if (vm_abort_flag) return false;
    uint32_t left = ms - (millis() - start);
    vTaskDelay(pdMS_TO_TICKS(left > 20 ? 20 : (left ? left : 1)));
  }
  return !vm_abort_flag;
}
uint32_t hal_random() { return esp_random(); }
void hal_pin_mode(int pin, int mode) { pinMode(pin, mode); }
void hal_digital_write(int pin, int v) { digitalWrite(pin, v ? HIGH : LOW); }
int hal_digital_read(int pin) { return digitalRead(pin); }
int hal_analog_read(int pin) { return analogRead(pin); }
void hal_analog_write(int pin, int v) { analogWrite(pin, v < 0 ? 0 : (v > 255 ? 255 : v)); }
int hal_touch_read(int pin) { return touchRead(pin); }
float hal_chip_temperature() { return temperatureRead(); }
void hal_tone(int pin, int freq, int ms) {
  if (freq <= 0) noTone(pin);
  else tone(pin, freq, ms);
}
void hal_poll() {}
uint32_t hal_largest_free_block() { return heap_caps_get_largest_free_block(MALLOC_CAP_8BIT); }
const char* hal_board_name() { return "esp32"; }
int hal_board_led_pin() { return 2; }

// ------------------------------------------------------------------ network HAL
bool hal_wifi_connect(const char* ssid, const char* pass, int timeout_ms) {
  if (WiFi.status() == WL_CONNECTED && WiFi.SSID() == ssid) return true;
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.begin(ssid, pass);
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (vm_abort_flag || (timeout_ms > 0 && (int)(millis() - t0) > timeout_ms)) return false;
    vTaskDelay(pdMS_TO_TICKS(100));
  }
  return true;
}
const char* hal_wifi_ip() {
  static char ip[20];
  if (WiFi.status() != WL_CONNECTED) return "";
  snprintf(ip, sizeof ip, "%s", WiFi.localIP().toString().c_str());
  return ip;
}

#define MAX_SOCK 4
static WiFiClient* g_sock[MAX_SOCK];

int hal_tcp_connect(const char* host, int port, bool tls, int timeout_ms) {
  int h = -1;
  for (int i = 0; i < MAX_SOCK; i++) if (!g_sock[i]) { h = i; break; }
  if (h < 0) return -1;
  WiFiClient* c;
  if (tls) {
    WiFiClientSecure* s = new WiFiClientSecure();
    s->setInsecure();  // no CA store on the device: encrypted but not authenticated
    c = s;
  } else {
    c = new WiFiClient();
  }
  if (!c->connect(host, port, timeout_ms)) { delete c; return -1; }
  c->setNoDelay(true);
  g_sock[h] = c;
  return h;
}
int hal_tcp_write(int h, const uint8_t* d, int n) {
  if (h < 0 || h >= MAX_SOCK || !g_sock[h] || !g_sock[h]->connected()) return -1;
  size_t w = g_sock[h]->write(d, n);
  return w == 0 ? -1 : (int)w;
}
int hal_tcp_read(int h, uint8_t* d, int n, int timeout_ms) {
  if (h < 0 || h >= MAX_SOCK || !g_sock[h]) return -1;
  WiFiClient* c = g_sock[h];
  uint32_t t0 = millis();
  while (true) {
    int av = c->available();
    if (av > 0) {
      int r = c->read(d, av < n ? av : n);
      return r < 0 ? -1 : r;
    }
    if (!c->connected()) return -1;
    if ((int)(millis() - t0) >= timeout_ms || vm_abort_flag) return 0;
    vTaskDelay(pdMS_TO_TICKS(5));
  }
}
void hal_tcp_close(int h) {
  if (h < 0 || h >= MAX_SOCK || !g_sock[h]) return;
  g_sock[h]->stop();
  delete g_sock[h];
  g_sock[h] = nullptr;
}

// ------------------------------------------------------------------ TFT over SPI (real ILI9341 module)
static SPIClass* g_spi = nullptr;
static SPISettings g_spi_cfg;
static int g_cs = -1, g_dc = -1, g_rst = -1, g_bl = -1;

static inline void spi_begin_tx() { g_spi->beginTransaction(g_spi_cfg); if (g_cs >= 0) digitalWrite(g_cs, LOW); }
static inline void spi_end_tx() { if (g_cs >= 0) digitalWrite(g_cs, HIGH); g_spi->endTransaction(); }
static void spi_cmd(uint8_t c) {
  digitalWrite(g_dc, LOW);
  spi_begin_tx(); g_spi->write(c); spi_end_tx();
  digitalWrite(g_dc, HIGH);
}
static void spi_data(const uint8_t* d, size_t n) {
  spi_begin_tx(); g_spi->writeBytes(d, n); spi_end_tx();
}
static void spi_fill(uint16_t c, uint32_t count) {
  uint8_t px[2] = {(uint8_t)(c >> 8), (uint8_t)c};
  spi_begin_tx(); g_spi->writePattern(px, 2, count); spi_end_tx();
}
static void spi_mono(const uint8_t* bits, uint32_t n, uint16_t fg, uint16_t bg) {
  uint8_t buf[128];
  size_t k = 0;
  spi_begin_tx();
  for (uint32_t i = 0; i < n; i++) {
    uint16_t c = (bits[i >> 3] & (0x80 >> (i & 7))) ? fg : bg;
    buf[k++] = c >> 8; buf[k++] = c;
    if (k == sizeof buf) { g_spi->writeBytes(buf, k); k = 0; }
  }
  if (k) g_spi->writeBytes(buf, k);
  spi_end_tx();
}
static void spi_reset(int level) { if (g_rst >= 0) digitalWrite(g_rst, level ? HIGH : LOW); }
static void spi_backlight(int level) { if (g_bl >= 0) digitalWrite(g_bl, level ? HIGH : LOW); }
static void spi_delay(int ms) { hal_delay(ms); }
static void spi_flush() {}
static const TftBus SPI_BUS = {spi_cmd, spi_data, spi_fill, spi_mono, spi_reset, spi_backlight, spi_delay, spi_flush};

const TftBus* hal_tft_spi_bus(const int pins[TP_COUNT], uint32_t hz) {
  if (pins[TP_SCK] < 0 || pins[TP_MOSI] < 0 || pins[TP_DC] < 0) return nullptr;
  if (!g_spi) g_spi = new SPIClass(HSPI);
  g_spi->end();
  g_spi->begin(pins[TP_SCK], pins[TP_MISO], pins[TP_MOSI], -1);
  g_spi_cfg = SPISettings(hz ? hz : 40000000, MSBFIRST, SPI_MODE0);
  g_cs = pins[TP_CS]; g_dc = pins[TP_DC]; g_rst = pins[TP_RST]; g_bl = pins[TP_BL];
  if (g_cs >= 0) { pinMode(g_cs, OUTPUT); digitalWrite(g_cs, HIGH); }
  pinMode(g_dc, OUTPUT); digitalWrite(g_dc, HIGH);
  if (g_rst >= 0) { pinMode(g_rst, OUTPUT); digitalWrite(g_rst, HIGH); }
  if (g_bl >= 0) { pinMode(g_bl, OUTPUT); digitalWrite(g_bl, LOW); }
  return &SPI_BUS;
}

// ------------------------------------------------------------------ device configuration
// Stored in LittleFS as "key=value\n" lines; the in-memory copy is guarded by a mutex (VM task reads it).
static char g_cfg[1024];
static SemaphoreHandle_t g_cfg_lock;

static void config_load() {
  g_cfg[0] = 0;
  if (!g_fs_ok || !LittleFS.exists(CONFIG_PATH)) return;
  File f = LittleFS.open(CONFIG_PATH, "r");
  size_t n = f.read((uint8_t*)g_cfg, sizeof g_cfg - 1);
  g_cfg[n] = 0;
  f.close();
}

static void config_save() {
  if (!g_fs_ok) return;
  File f = LittleFS.open(CONFIG_PATH, "w");
  f.write((const uint8_t*)g_cfg, strlen(g_cfg));
  f.close();
}

bool hal_config_get(const char* key, char* out, size_t n) {
  bool found = false;
  size_t kl = strlen(key);
  xSemaphoreTake(g_cfg_lock, portMAX_DELAY);
  for (const char* l = g_cfg; *l;) {
    const char* e = strchr(l, '\n');
    size_t ll = e ? (size_t)(e - l) : strlen(l);
    if (ll > kl && !strncmp(l, key, kl) && l[kl] == '=') {
      size_t vl = ll - kl - 1;
      if (vl >= n) vl = n - 1;
      memcpy(out, l + kl + 1, vl);
      out[vl] = 0;
      found = true;
      break;
    }
    l += ll + (e ? 1 : 0);
  }
  xSemaphoreGive(g_cfg_lock);
  return found;
}

// Sets (or with an empty value removes) one key.
static bool config_set(const char* kv) {
  const char* eq = strchr(kv, '=');
  if (!eq || eq == kv) return false;
  size_t kl = eq - kv;
  char out[sizeof g_cfg];
  size_t o = 0;
  xSemaphoreTake(g_cfg_lock, portMAX_DELAY);
  for (const char* l = g_cfg; *l;) {
    const char* e = strchr(l, '\n');
    size_t ll = e ? (size_t)(e - l) : strlen(l);
    bool same = ll > kl && !strncmp(l, kv, kl) && l[kl] == '=';
    if (!same && ll && o + ll + 1 < sizeof out) { memcpy(out + o, l, ll); o += ll; out[o++] = '\n'; }
    l += ll + (e ? 1 : 0);
  }
  size_t vl = strlen(eq + 1);
  bool ok = true;
  if (vl) {
    size_t nl = kl + 1 + vl;
    if (o + nl + 1 < sizeof out) { memcpy(out + o, kv, nl); o += nl; out[o++] = '\n'; }
    else ok = false;
  }
  out[o] = 0;
  if (ok) memcpy(g_cfg, out, o + 1);
  xSemaphoreGive(g_cfg_lock);
  if (ok) config_save();
  return ok;
}

static void config_print() {
  Serial.print("[ktvm] config: ");
  xSemaphoreTake(g_cfg_lock, portMAX_DELAY);
  bool first = true;
  for (const char* p = g_cfg; *p; p++) {
    if (*p == '\n') { if (p[1]) Serial.print(';'); continue; }
    Serial.print(*p);
    first = false;
  }
  xSemaphoreGive(g_cfg_lock);
  if (first) Serial.print("(empty)");
  Serial.printf(" [id %s]\n", hal_device_id());
}

const char* hal_device_id() {
  static char id[16];
  if (!id[0]) {
    uint64_t mac = ESP.getEfuseMac();
    snprintf(id, sizeof id, "%02X%02X%02X%02X%02X%02X", (uint8_t)mac, (uint8_t)(mac >> 8), (uint8_t)(mac >> 16),
             (uint8_t)(mac >> 24), (uint8_t)(mac >> 32), (uint8_t)(mac >> 40));
  }
  return id;
}

// ------------------------------------------------------------------ storage
static uint8_t* load_program(size_t* len) {
  if (!g_fs_ok || !LittleFS.exists(PROG_PATH)) return nullptr;
  File f = LittleFS.open(PROG_PATH, "r");
  if (!f) return nullptr;
  size_t n = f.size();
  uint8_t* b = (uint8_t*)malloc(n);
  if (b && f.read(b, n) != n) { free(b); b = nullptr; }
  f.close();
  *len = n;
  return b;
}

static void save_program(const uint8_t* b, size_t n) {
  if (!g_fs_ok) return;
  File f = LittleFS.open(PROG_PATH, "w");
  if (!f) return;
  f.write(b, n);
  f.close();
}

// ------------------------------------------------------------------ VM task
static void run_image(uint8_t* img, size_t len) {
  char err[96];
  vm_abort_flag = false;
  g_state = VM_RUNNING;
  // Leave room outside the Kotlin heap for the WiFi stack and TLS (vm_load shrinks it if it doesn't fit).
  if (!vm_load(img, len, KTVM_MAX_HEAP, err, sizeof err)) {
    Serial.printf("\n[ktvm] load error: %s\n", err);
  } else {
    Serial.printf("[ktvm] running (image %u B, heap %u B)\n", (unsigned)len, (unsigned)vm_heap_free());
    uint32_t t0 = millis();
    int st = vm_run();
    net_reset();
    for (int i = 0; i < MAX_SOCK; i++) hal_tcp_close(i);
    vm_unload();
    uint32_t dt = millis() - t0;
    if (st == ST_OK) Serial.printf("\n[ktvm] program finished in %u ms\n", (unsigned)dt);
    else if (st == ST_ABORT) Serial.printf("\n[ktvm] program stopped\n");
    else Serial.printf("\n[ktvm] program terminated with an error\n");
  }
  g_state = VM_IDLE;
}

static void vm_task(void*) {
  size_t len = 0;
  uint8_t* img = load_program(&len);
  if (img) {
    Serial.println("[ktvm] starting stored program");
    run_image(img, len);
    free(img);
  } else {
    Serial.println("[ktvm] no program stored yet: run `./ktesp run examples/Blink.kt`");
  }
  for (;;) {
    if (g_pending) {
      img = g_pending;
      len = g_pending_len;
      g_pending = nullptr;
      save_program(img, len);
      run_image(img, len);
      free(img);
    } else if (g_rerun) {
      g_rerun = false;
      img = load_program(&len);
      if (img) { run_image(img, len); free(img); }
    } else {
      vTaskDelay(pdMS_TO_TICKS(20));
    }
  }
}

// ------------------------------------------------------------------ upload protocol
static uint32_t crc32(const uint8_t* p, size_t n) {
  uint32_t c = 0xFFFFFFFFu;
  while (n--) {
    c ^= *p++;
    for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1)));
  }
  return ~c;
}

static bool read_exact(uint8_t* d, size_t n, uint32_t timeout_ms) {
  size_t got = 0;
  uint32_t last = millis();
  while (got < n) {
    int avail = Serial.available();
    if (avail > 0) {
      size_t want = n - got < (size_t)avail ? n - got : (size_t)avail;
      got += Serial.readBytes(d + got, want);
      last = millis();
    } else {
      if (millis() - last > timeout_ms) return false;
      delay(1);
    }
  }
  return true;
}

static void stop_vm() {
  vm_abort_flag = true;
  uint32_t t0 = millis();
  while (g_state == VM_RUNNING && millis() - t0 < 3000) delay(5);
}

static void receive_upload() {
  uint8_t hdr[8];
  if (!read_exact(hdr, 8, 1000)) { Serial.println("\n[ktvm] upload error: header timeout"); return; }
  uint32_t len = hdr[0] | (hdr[1] << 8) | (hdr[2] << 16) | ((uint32_t)hdr[3] << 24);
  uint32_t crc = hdr[4] | (hdr[5] << 8) | (hdr[6] << 16) | ((uint32_t)hdr[7] << 24);
  if (len == 0 || len > MAX_IMAGE) { Serial.printf("\n[ktvm] upload error: bad size %u\n", (unsigned)len); return; }
  stop_vm();
  if (g_state == VM_RUNNING) { Serial.println("\n[ktvm] upload error: program did not stop"); return; }
  if (g_pending) { free(g_pending); g_pending = nullptr; }
  uint8_t* buf = (uint8_t*)malloc(len);
  if (!buf) { Serial.printf("\n[ktvm] upload error: no memory for %u bytes\n", (unsigned)len); return; }
  Serial.print("\n[ktvm] ready\n");
  for (size_t got = 0; got < len;) {
    size_t n = len - got < 512 ? len - got : 512;
    if (!read_exact(buf + got, n, 3000)) { free(buf); Serial.println("\n[ktvm] upload error: timeout"); return; }
    got += n;
    Serial.write((uint8_t)0x06);
  }
  if (crc32(buf, len) != crc) { free(buf); Serial.println("\n[ktvm] upload error: bad checksum"); return; }
  Serial.printf("\n[ktvm] upload ok (%u bytes)\n", (unsigned)len);
  g_pending_len = len;
  g_pending = buf;
}

static void receive_config() {
  uint8_t hl[2];
  if (!read_exact(hl, 2, 1000)) { Serial.println("\n[ktvm] config error: timeout"); return; }
  size_t n = hl[0] | (hl[1] << 8);
  if (n >= 200) { Serial.println("\n[ktvm] config error: too long"); return; }
  char kv[200];
  if (n && !read_exact((uint8_t*)kv, n, 1000)) { Serial.println("\n[ktvm] config error: timeout"); return; }
  kv[n] = 0;
  if (n) {
    if (!config_set(kv)) { Serial.println("\n[ktvm] config error: expected key=value"); return; }
    if (g_state == VM_RUNNING) { stop_vm(); g_rerun = true; }  // restart so the program sees the new value
  }
  Serial.print("\n");
  config_print();
}

// ------------------------------------------------------------------ Arduino entry points
void setup() {
  Serial.setRxBufferSize(4096);
  Serial.begin(KTVM_BAUD);
  delay(50);
  g_fs_ok = LittleFS.begin(true);
  g_cfg_lock = xSemaphoreCreateMutex();
  config_load();
  Serial.printf("\n[ktvm] KTVM ready - Kotlin on ESP32 (free heap %u B, largest block %u B%s)\n",
                (unsigned)ESP.getFreeHeap(), (unsigned)hal_largest_free_block(), g_fs_ok ? "" : ", NO FILESYSTEM");
  config_print();
  xTaskCreatePinnedToCore(vm_task, "ktvm", VM_STACK_BYTES, nullptr, 1, nullptr, 1);
}

void loop() {
  static uint32_t window = 0;
  while (Serial.available()) {
    window = (window << 8) | (uint8_t)Serial.read();
    if (window == 0x7f4b5456) { window = 0; receive_upload(); }           // \x7f K T V
    else if (window == 0x7f4b5452) { window = 0; stop_vm(); g_rerun = true; }  // \x7f K T R
    else if (window == 0x7f4b5453) { window = 0; stop_vm(); }              // \x7f K T S
    else if (window == 0x7f4b5443) { window = 0; receive_config(); }       // \x7f K T C
  }
  delay(5);
}
