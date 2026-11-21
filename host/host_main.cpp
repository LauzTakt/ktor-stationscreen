// Desktop simulator: runs a .ktv image with the same VM as the firmware.
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <time.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#include "../firmware/src/hal.h"
#include "../firmware/src/ktvm.h"
#include "../firmware/src/display.h"

static bool g_trace_gpio = true;

static uint64_t now_us() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000ull + ts.tv_nsec / 1000;
}
static uint64_t t0;

void hal_write(const char* s, size_t n) { fwrite(s, 1, n, stdout); fflush(stdout); }
uint64_t hal_micros64() { return now_us() - t0; }
uint32_t hal_millis() { return (uint32_t)(hal_micros64() / 1000); }
uint32_t hal_micros() { return (uint32_t)hal_micros64(); }
bool hal_delay(uint32_t ms) { usleep(ms * 1000); return !vm_abort_flag; }
uint32_t hal_random() { return (uint32_t)arc4random(); }
void hal_pin_mode(int pin, int mode) { if (g_trace_gpio) fprintf(stderr, "[sim] pinMode(%d, %d)\n", pin, mode); }
void hal_digital_write(int pin, int v) { if (g_trace_gpio) fprintf(stderr, "[sim] GPIO%d = %s\n", pin, v ? "HIGH" : "LOW"); }
int hal_digital_read(int) { return 0; }
int hal_analog_read(int) { return (int)(arc4random() % 4096); }
void hal_analog_write(int pin, int v) { if (g_trace_gpio) fprintf(stderr, "[sim] PWM%d = %d\n", pin, v); }
int hal_touch_read(int) { return 50; }
float hal_chip_temperature() { return 42.0f; }
void hal_tone(int pin, int f, int ms) { if (g_trace_gpio) fprintf(stderr, "[sim] tone(%d, %dHz, %dms)\n", pin, f, ms); }
void hal_poll() {}
uint32_t hal_largest_free_block() { return 96 * 1024; }
const char* hal_board_name() { return "simulator"; }
int hal_board_led_pin() { return 2; }

// ---- display: no SPI on the computer, only the link to the emulator
const TftBus* hal_tft_spi_bus(const int*, uint32_t) {
  fprintf(stderr, "[sim] TFT over SPI is only available on the ESP32 (use Screen.simulated)\n");
  return nullptr;
}

// ---- network: the Mac's own connection plays the role of the WiFi
bool hal_wifi_connect(const char* ssid, const char*, int) {
  fprintf(stderr, "[sim] wifiConnect(\"%s\") -> using the computer's network\n", ssid);
  return true;
}
const char* hal_wifi_ip() { return "127.0.0.1"; }
int hal_tcp_connect(const char* host, int port, bool tls, int timeout_ms) {
  if (tls) { fprintf(stderr, "[sim] https/wss is not supported by the simulator (use http:// or ws://)\n"); return -1; }
  struct addrinfo hints = {}, *res = nullptr;
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_STREAM;
  char ps[8];
  snprintf(ps, sizeof ps, "%d", port);
  if (getaddrinfo(host, ps, &hints, &res) || !res) return -1;
  int fd = -1;
  for (struct addrinfo* ai = res; ai; ai = ai->ai_next) {
    fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
    if (fd < 0) continue;
    fcntl(fd, F_SETFL, O_NONBLOCK);
    int r = connect(fd, ai->ai_addr, ai->ai_addrlen);
    if (r < 0 && errno == EINPROGRESS) {
      struct pollfd pf = {fd, POLLOUT, 0};
      int err = 0;
      socklen_t el = sizeof err;
      if (poll(&pf, 1, timeout_ms) == 1 && !getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el) && !err) r = 0;
    }
    if (r == 0) break;
    close(fd);
    fd = -1;
  }
  freeaddrinfo(res);
  if (fd >= 0) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
  }
  return fd;
}
int hal_tcp_write(int h, const uint8_t* d, int n) {
  struct pollfd pf = {h, POLLOUT, 0};
  if (poll(&pf, 1, 5000) != 1) return -1;
  ssize_t w = send(h, d, n, 0);
  return w < 0 ? (errno == EAGAIN ? 0 : -1) : (int)w;
}
int hal_tcp_read(int h, uint8_t* d, int n, int timeout_ms) {
  struct pollfd pf = {h, POLLIN, 0};
  int p = poll(&pf, 1, timeout_ms);
  if (p < 0) return -1;
  if (p == 0) return 0;
  ssize_t r = recv(h, d, n, 0);
  if (r < 0) return errno == EAGAIN ? 0 : -1;
  return r == 0 ? -1 : (int)r;
}
void hal_tcp_close(int h) { if (h >= 0) close(h); }

// ---- device configuration: --config key=value (repeatable)
static const char* g_config[16];
static int g_nconfig = 0;
bool hal_config_get(const char* key, char* out, size_t n) {
  size_t kl = strlen(key);
  for (int i = 0; i < g_nconfig; i++)
    if (!strncmp(g_config[i], key, kl) && g_config[i][kl] == '=') {
      snprintf(out, n, "%s", g_config[i] + kl + 1);
      return out[0] != 0;
    }
  return false;
}
const char* hal_device_id() { return "SIMULATOR"; }

int main(int argc, char** argv) {
  signal(SIGPIPE, SIG_IGN);
  const char* path = nullptr;
  unsigned heap_kb = 96;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "-q")) g_trace_gpio = false;
    else if (!strcmp(argv[i], "--heap") && i + 1 < argc) heap_kb = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--config") && i + 1 < argc) { if (g_nconfig < 16) g_config[g_nconfig++] = argv[++i]; }
    else path = argv[i];
  }
  if (!path) { fprintf(stderr, "usage: ktvm-sim [-q] [--heap KB] [--config key=value]... image.ktv\n"); return 2; }
  FILE* f = fopen(path, "rb");
  if (!f) { perror(path); return 2; }
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  uint8_t* buf = (uint8_t*)malloc(n);
  if (fread(buf, 1, n, f) != (size_t)n) { perror("read"); return 2; }
  fclose(f);
  t0 = now_us();
  char err[128];
  if (!vm_load(buf, n, heap_kb * 1024, err, sizeof err)) { fprintf(stderr, "load error: %s\n", err); return 2; }
  int st = vm_run();
  vm_unload();
  free(buf);
  return st == 0 ? 0 : 1;
}
