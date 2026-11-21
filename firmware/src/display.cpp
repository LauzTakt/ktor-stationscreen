// ILI9341 driver + the "link" bus that tunnels the SPI stream to the emulator on the computer.
#include "display.h"

#include <string.h>

#include "font5x7.h"
#include "hal.h"

// ---------------------------------------------------------------- link bus (USB serial tunnel)
// Frame: 0x1E 'L' len(u16 LE) payload[len] xor8(payload)
// Payload ops (they encode the exact SPI byte stream; fill/mono are just run-length / 1-bit encodings of it):
//   01 c                       command byte (D/C=0)
//   02 n d[n]                  n data bytes (D/C=1)
//   03 hi lo count(u32 LE)     data: pixel (hi,lo) repeated count times
//   04 fh fl bh bl n(u16 LE) bits[(n+7)/8]   data: n pixels, bit=1 -> (fh,fl), 0 -> (bh,bl), MSB first
//   05 level                   RST line      06 level   backlight line      07 ms(u16 LE)  delay
static uint8_t lk_buf[1024];
static size_t lk_n = 0;

static void lk_flush() {
  if (!lk_n) return;
  uint8_t hdr[4] = {0x1E, 'L', (uint8_t)lk_n, (uint8_t)(lk_n >> 8)};
  uint8_t x = 0;
  for (size_t i = 0; i < lk_n; i++) x ^= lk_buf[i];
  hal_write((const char*)hdr, 4);
  hal_write((const char*)lk_buf, lk_n);
  hal_write((const char*)&x, 1);
  lk_n = 0;
}
static uint8_t* lk_reserve(size_t n) {
  if (lk_n + n > sizeof lk_buf) lk_flush();
  uint8_t* p = lk_buf + lk_n;
  lk_n += n;
  return p;
}
static void lk_cmd(uint8_t c) { uint8_t* p = lk_reserve(2); p[0] = 1; p[1] = c; }
static void lk_data(const uint8_t* d, size_t n) {
  while (n) {
    size_t k = n > 255 ? 255 : n;
    uint8_t* p = lk_reserve(2 + k);
    p[0] = 2; p[1] = (uint8_t)k;
    memcpy(p + 2, d, k);
    d += k; n -= k;
  }
}
static void lk_fill(uint16_t c, uint32_t count) {
  uint8_t* p = lk_reserve(7);
  p[0] = 3; p[1] = c >> 8; p[2] = c & 0xFF;
  p[3] = count; p[4] = count >> 8; p[5] = count >> 16; p[6] = count >> 24;
}
static void lk_mono(const uint8_t* bits, uint32_t n, uint16_t fg, uint16_t bg) {
  while (n) {
    uint32_t k = n > 4096 ? 4096 : n;  // 512 bytes of bits max per op
    size_t nb = (k + 7) / 8;
    uint8_t* p = lk_reserve(7 + nb);
    p[0] = 4; p[1] = fg >> 8; p[2] = fg & 0xFF; p[3] = bg >> 8; p[4] = bg & 0xFF; p[5] = k; p[6] = k >> 8;
    memcpy(p + 7, bits, nb);
    bits += nb; n -= k;  // k is a multiple of 8 unless it's the last chunk
  }
}
static void lk_reset(int level) { uint8_t* p = lk_reserve(2); p[0] = 5; p[1] = (uint8_t)level; }
static void lk_backlight(int level) { uint8_t* p = lk_reserve(2); p[0] = 6; p[1] = (uint8_t)level; }
static void lk_delay(int ms) { uint8_t* p = lk_reserve(3); p[0] = 7; p[1] = ms; p[2] = ms >> 8; }

static const TftBus LINK_BUS = {lk_cmd, lk_data, lk_fill, lk_mono, lk_reset, lk_backlight, lk_delay, lk_flush};

// ---------------------------------------------------------------- driver state
static const TftBus* bus = nullptr;
static int W = 240, H = 320;
static int win_x0 = -1, win_x1 = -1, win_y0 = -1, win_y1 = -1;
static uint8_t madctl = 0;

bool tft_ready() { return bus != nullptr; }
int tft_width() { return W; }
int tft_height() { return H; }

static void command(uint8_t c, const uint8_t* d = nullptr, size_t n = 0) {
  bus->cmd(c);
  if (n) bus->data(d, n);
}

// Initialisation sequence of Adafruit_ILI9341::begin() (values from the ILI9341 datasheet / panel vendor)
static const uint8_t INIT[] = {
    0xEF, 3, 0x03, 0x80, 0x02,
    0xCF, 3, 0x00, 0xC1, 0x30,           // power control B
    0xED, 4, 0x64, 0x03, 0x12, 0x81,     // power on sequence control
    0xE8, 3, 0x85, 0x00, 0x78,           // driver timing control A
    0xCB, 5, 0x39, 0x2C, 0x00, 0x34, 0x02,  // power control A
    0xF7, 1, 0x20,                       // pump ratio control
    0xEA, 2, 0x00, 0x00,                 // driver timing control B
    0xC0, 1, 0x23,                       // PWCTR1: VRH
    0xC1, 1, 0x10,                       // PWCTR2: SAP, BT
    0xC5, 2, 0x3E, 0x28,                 // VMCTR1: VCOM
    0xC7, 1, 0x86,                       // VMCTR2
    ILI_MADCTL, 1, 0x48,                 // memory access control (MX | BGR)
    ILI_VSCRSADD, 1, 0x00,               // vertical scroll start = 0
    ILI_PIXFMT, 1, 0x55,                 // COLMOD: 16 bits/pixel (RGB565)
    0xB1, 2, 0x00, 0x18,                 // FRMCTR1: 79 Hz
    0xB6, 3, 0x08, 0x82, 0x27,           // display function control
    0xF2, 1, 0x00,                       // 3-gamma off
    ILI_GAMMASET, 1, 0x01,               // gamma curve 1
    0xE0, 15, 0x0F, 0x31, 0x2B, 0x0C, 0x0E, 0x08, 0x4E, 0xF1, 0x37, 0x07, 0x10, 0x03, 0x0E, 0x09, 0x00,
    0xE1, 15, 0x00, 0x0E, 0x14, 0x03, 0x11, 0x07, 0x31, 0xC1, 0x48, 0x08, 0x0F, 0x0C, 0x31, 0x36, 0x0F,
    ILI_SLPOUT, 0x80,                    // sleep out, then wait 150 ms
    ILI_DISPON, 0x80,                    // display on, then wait 150 ms
    0x00,
};

static void set_rotation(int r) {
  static const uint8_t M[4] = {MADCTL_MX | MADCTL_BGR, MADCTL_MV | MADCTL_BGR, MADCTL_MY | MADCTL_BGR,
                               MADCTL_MX | MADCTL_MY | MADCTL_MV | MADCTL_BGR};
  r &= 3;
  madctl = M[r];
  W = (r & 1) ? 320 : 240;
  H = (r & 1) ? 240 : 320;
  command(ILI_MADCTL, &madctl, 1);
  win_x0 = win_x1 = win_y0 = win_y1 = -1;
}

bool tft_begin(int mode, int rotation, const int pins[TP_COUNT], uint32_t spi_hz) {
  bus = mode == TFT_SPI ? hal_tft_spi_bus(pins, spi_hz) : &LINK_BUS;
  if (!bus) return false;
  int rst = pins[TP_RST];
  if (rst >= 0) {  // hardware reset pulse
    bus->reset(1); bus->delay(5);
    bus->reset(0); bus->delay(20);
    bus->reset(1); bus->delay(150);
  } else {
    command(ILI_SWRESET);
    bus->delay(150);
  }
  for (const uint8_t* p = INIT; *p;) {
    uint8_t c = *p++, x = *p++, n = x & 0x7F;
    command(c, p, n);
    p += n;
    if (x & 0x80) bus->delay(150);
  }
  set_rotation(rotation);
  bus->backlight(1);
  bus->flush();
  return true;
}

void tft_flush() { if (bus) bus->flush(); }
void tft_invert(bool on) { if (bus) command(on ? ILI_INVON : ILI_INVOFF); }
void tft_backlight(bool on) { if (bus) bus->backlight(on ? 1 : 0); }

// Address window (CASET/PASET skipped when unchanged, like Adafruit_ILI9341::setAddrWindow), then RAMWR.
static void window(int x0, int y0, int x1, int y1) {
  uint8_t d[4];
  if (x0 != win_x0 || x1 != win_x1) {
    d[0] = x0 >> 8; d[1] = x0; d[2] = x1 >> 8; d[3] = x1;
    command(ILI_CASET, d, 4);
    win_x0 = x0; win_x1 = x1;
  }
  if (y0 != win_y0 || y1 != win_y1) {
    d[0] = y0 >> 8; d[1] = y0; d[2] = y1 >> 8; d[3] = y1;
    command(ILI_PASET, d, 4);
    win_y0 = y0; win_y1 = y1;
  }
  bus->cmd(ILI_RAMWR);
}

// ---------------------------------------------------------------- primitives
void tft_fill_rect(int x, int y, int w, int h, uint16_t c) {
  if (!bus) return;
  if (x < 0) { w += x; x = 0; }
  if (y < 0) { h += y; y = 0; }
  if (x + w > W) w = W - x;
  if (y + h > H) h = H - y;
  if (w <= 0 || h <= 0) return;
  window(x, y, x + w - 1, y + h - 1);
  bus->fill(c, (uint32_t)w * h);
}

void tft_pixel(int x, int y, uint16_t c) {
  if (!bus || x < 0 || y < 0 || x >= W || y >= H) return;
  window(x, y, x, y);
  uint8_t d[2] = {(uint8_t)(c >> 8), (uint8_t)c};
  bus->data(d, 2);
}

void tft_line(int x0, int y0, int x1, int y1, uint16_t c) {
  if (y0 == y1) { if (x1 < x0) { int t = x0; x0 = x1; x1 = t; } tft_fill_rect(x0, y0, x1 - x0 + 1, 1, c); return; }
  if (x0 == x1) { if (y1 < y0) { int t = y0; y0 = y1; y1 = t; } tft_fill_rect(x0, y0, 1, y1 - y0 + 1, c); return; }
  int dx = x1 > x0 ? x1 - x0 : x0 - x1, sx = x0 < x1 ? 1 : -1;
  int dy = y1 > y0 ? y0 - y1 : y1 - y0, sy = y0 < y1 ? 1 : -1;
  int err = dx + dy;
  for (;;) {  // Bresenham
    tft_pixel(x0, y0, c);
    if (x0 == x1 && y0 == y1) break;
    int e2 = 2 * err;
    if (e2 >= dy) { err += dy; x0 += sx; }
    if (e2 <= dx) { err += dx; y0 += sy; }
  }
}

void tft_rect(int x, int y, int w, int h, uint16_t c) {
  if (w <= 0 || h <= 0) return;
  tft_fill_rect(x, y, w, 1, c);
  tft_fill_rect(x, y + h - 1, w, 1, c);
  tft_fill_rect(x, y + 1, 1, h - 2, c);
  tft_fill_rect(x + w - 1, y + 1, 1, h - 2, c);
}

// Adafruit_GFX::fillCircleHelper: vertical spans of the left (2) / right (1) halves
static void circle_helper(int x0, int y0, int r, int corners, int delta, uint16_t c) {
  int f = 1 - r, ddx = 1, ddy = -2 * r, x = 0, y = r, px = x, py = y;
  delta++;
  while (x < y) {
    if (f >= 0) { y--; ddy += 2; f += ddy; }
    x++; ddx += 2; f += ddx;
    if (x < y + 1) {
      if (corners & 1) tft_fill_rect(x0 + x, y0 - y, 1, 2 * y + delta, c);
      if (corners & 2) tft_fill_rect(x0 - x, y0 - y, 1, 2 * y + delta, c);
    }
    if (y != py) {
      if (corners & 1) tft_fill_rect(x0 + py, y0 - px, 1, 2 * px + delta, c);
      if (corners & 2) tft_fill_rect(x0 - py, y0 - px, 1, 2 * px + delta, c);
      py = y;
    }
    px = x;
  }
}

void tft_fill_circle(int x, int y, int r, uint16_t c) {
  if (r < 0) return;
  tft_fill_rect(x, y - r, 1, 2 * r + 1, c);
  circle_helper(x, y, r, 3, 0, c);
}

void tft_fill_round_rect(int x, int y, int w, int h, int r, uint16_t c) {
  int m = (w < h ? w : h) / 2;
  if (r > m) r = m;
  if (r <= 0) { tft_fill_rect(x, y, w, h, c); return; }
  tft_fill_rect(x + r, y, w - 2 * r, h, c);
  circle_helper(x + w - r - 1, y + r, r, 1, h - 2 * r - 1, c);
  circle_helper(x + r, y + r, r, 2, h - 2 * r - 1, c);
}

// ---------------------------------------------------------------- text
// UTF-8 -> CP437 glyph index (the font's layout), for the accents a Spanish/Catalan text needs.
static int next_glyph(const uint8_t* s, size_t n, size_t* i) {
  uint32_t c = s[(*i)++];
  if (c >= 0x80) {
    int extra = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC0 ? 1 : 0;
    c &= extra == 3 ? 0x07 : extra == 2 ? 0x0F : 0x1F;
    while (extra-- && *i < n) c = (c << 6) | (s[(*i)++] & 0x3F);
  }
  if (c < 0x80) return (int)c;
  switch (c) {
    case 0xE1: return 0xA0; case 0xE9: return 0x82; case 0xED: return 0xA1; case 0xF3: return 0xA2;
    case 0xFA: return 0xA3; case 0xF1: return 0xA4; case 0xD1: return 0xA5; case 0xFC: return 0x81;
    case 0xDC: return 0x9A; case 0xE7: return 0x87; case 0xC7: return 0x80; case 0xC9: return 0x90;
    case 0xE0: return 0x85; case 0xE8: return 0x8A; case 0xF2: return 0x95; case 0xEF: return 0x8B;
    case 0xE2: return 0x83; case 0xEA: return 0x88; case 0xF4: return 0x93; case 0xBF: return 0xA8;
    case 0xA1: return 0xAD; case 0xB7: return 0xFA; case 0xB0: return 0xF8; case 0xAA: return 0xA6;
    case 0xBA: return 0xA7; case 0x20AC: return 'E'; case 0x2192: return 0x1A; case 0x2190: return 0x1B;
    case 0xC1: return 'A'; case 0xCD: return 'I'; case 0xD3: return 'O'; case 0xDA: return 'U';
    case 0x2022: return 0x07; case 0x2026: return '.';
    case 0x2013: case 0x2014: case 0x2212: return '-'; case 0x2018: case 0x2019: return '\'';
    case 0x201C: case 0x201D: return '"'; case 0xA0: return ' ';
    default: return '?';
  }
}

void tft_text(int x, int y, const char* str, size_t n, uint16_t fg, int32_t bg, int size) {
  if (!bus || size < 1) return;
  static int glyphs[96];
  int ng = 0;
  for (size_t i = 0; i < n && ng < 96;) glyphs[ng++] = next_glyph((const uint8_t*)str, n, &i);
  if (!ng) return;
  int cw = 6 * size, ch = 8 * size;
  if (bg >= 0) {
    // Opaque: one address window for the whole string, 1 bit per pixel (like TFT_eSPI's block text).
    int x0 = x < 0 ? 0 : x, y0 = y < 0 ? 0 : y;
    int x1 = x + cw * ng - 1, y1 = y + ch - 1;
    if (x1 >= W) x1 = W - 1;
    if (y1 >= H) y1 = H - 1;
    if (x0 > x1 || y0 > y1) return;
    int ww = x1 - x0 + 1;
    window(x0, y0, x1, y1);
    static uint8_t bits[(320 * 16 + 7) / 8 + 1];
    uint32_t nbit = 0;
    int rows_per_chunk = (int)((sizeof bits * 8) / ww);
    for (int py = y0; py <= y1; py++) {
      int row = (py - y) / size;
      for (int px = x0; px <= x1; px++) {
        int col = (px - x) / size, k = col / 6, cx = col % 6;
        bool on = cx < 5 && row < 8 && ((font5x7[glyphs[k] * 5 + cx] >> row) & 1);
        if ((nbit & 7) == 0) bits[nbit >> 3] = 0;
        if (on) bits[nbit >> 3] |= 0x80 >> (nbit & 7);
        nbit++;
      }
      if ((py - y0 + 1) % rows_per_chunk == 0 || py == y1) {
        bus->mono(bits, nbit, fg, (uint16_t)bg);
        nbit = 0;
      }
    }
    return;
  }
  // Transparent: vertical runs of each glyph column (Adafruit_GFX::drawChar)
  for (int k = 0; k < ng; k++) {
    int gx = x + k * cw;
    if (gx >= W) break;
    for (int cx = 0; cx < 5; cx++) {
      uint8_t line = font5x7[glyphs[k] * 5 + cx];
      int run = 0;
      for (int j = 0; j <= 8; j++) {
        bool on = j < 8 && ((line >> j) & 1);
        if (on) run++;
        else if (run) {
          tft_fill_rect(gx + cx * size, y + (j - run) * size, size, run * size, fg);
          run = 0;
        }
      }
    }
  }
}
