// ILI9341 TFT driver (240x320, 16-bit RGB565, 4-wire SPI with D/C line).
//
// Every drawing call becomes the ILI9341 command/data byte stream (CASET/PASET/RAMWR + pixels), exactly
// what Adafruit_ILI9341 sends. That stream goes to a TftBus:
//   - SPI bus (main.cpp): a real ILI9341 module wired to the ESP32 (e.g. ESP32-2432S028R "Cheap Yellow Display");
//   - link bus (display.cpp): the same bytes, plus the RST/backlight lines, tunnelled over the USB serial port
//     to the ILI9341 emulator on the computer (`./ktesp screen`).
#pragma once
#include <stddef.h>
#include <stdint.h>

struct TftBus {
  void (*cmd)(uint8_t c);                                   // D/C low
  void (*data)(const uint8_t* d, size_t n);                 // D/C high
  void (*fill)(uint16_t color, uint32_t count);             // data: the same RGB565 pixel `count` times
  void (*mono)(const uint8_t* bits, uint32_t n, uint16_t fg, uint16_t bg);  // data: n pixels, 1 bit each (MSB first)
  void (*reset)(int level);                                 // RST line (-1 = not wired)
  void (*backlight)(int level);                             // backlight line
  void (*delay)(int ms);
  void (*flush)();
};

enum { TFT_LINK = 0, TFT_SPI = 1 };
enum { TP_SCK, TP_MOSI, TP_MISO, TP_CS, TP_DC, TP_RST, TP_BL, TP_COUNT };

// ILI9341 commands (datasheet section 8)
#define ILI_SWRESET 0x01
#define ILI_SLPOUT 0x11
#define ILI_INVOFF 0x20
#define ILI_INVON 0x21
#define ILI_GAMMASET 0x26
#define ILI_DISPOFF 0x28
#define ILI_DISPON 0x29
#define ILI_CASET 0x2A
#define ILI_PASET 0x2B
#define ILI_RAMWR 0x2C
#define ILI_MADCTL 0x36
#define ILI_VSCRSADD 0x37
#define ILI_PIXFMT 0x3A
#define MADCTL_MY 0x80
#define MADCTL_MX 0x40
#define MADCTL_MV 0x20
#define MADCTL_BGR 0x08

bool tft_begin(int mode, int rotation, const int pins[TP_COUNT], uint32_t spi_hz);
bool tft_ready();
int tft_width();
int tft_height();
void tft_fill_rect(int x, int y, int w, int h, uint16_t c);
void tft_pixel(int x, int y, uint16_t c);
void tft_line(int x0, int y0, int x1, int y1, uint16_t c);
void tft_rect(int x, int y, int w, int h, uint16_t c);
void tft_fill_circle(int x, int y, int r, uint16_t c);
void tft_fill_round_rect(int x, int y, int w, int h, int r, uint16_t c);
// UTF-8 text with the classic 6x8 font (CP437 glyphs for accents). bg < 0 = transparent.
void tft_text(int x, int y, const char* s, size_t n, uint16_t fg, int32_t bg, int size);
void tft_invert(bool on);
void tft_backlight(bool on);
void tft_flush();

static inline uint16_t rgb565(uint32_t rgb) {
  return (uint16_t)(((rgb >> 8) & 0xF800) | ((rgb >> 5) & 0x07E0) | ((rgb >> 3) & 0x001F));
}

// Provided by the platform: a real SPI bus (ESP32) or nullptr when there is none (simulator).
const TftBus* hal_tft_spi_bus(const int pins[TP_COUNT], uint32_t hz);
