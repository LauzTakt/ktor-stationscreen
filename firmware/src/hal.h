// Hardware abstraction used by the VM natives. Implemented by main.cpp (ESP32) and host/host_hal.cpp.
#pragma once
#include <stddef.h>
#include <stdint.h>

void hal_write(const char* s, size_t n);
uint32_t hal_millis();
uint32_t hal_micros();
uint64_t hal_micros64();
bool hal_delay(uint32_t ms);  // returns false if aborted while sleeping
uint32_t hal_random();
void hal_pin_mode(int pin, int mode);
void hal_digital_write(int pin, int v);
int hal_digital_read(int pin);
int hal_analog_read(int pin);
void hal_analog_write(int pin, int v);
int hal_touch_read(int pin);
float hal_chip_temperature();
void hal_tone(int pin, int freq, int ms);
void hal_poll();  // called periodically from the interpreter loop
uint32_t hal_largest_free_block();
const char* hal_board_name();  // "esp32", "raspberrypi", "linux", "simulator"...
int hal_board_led_pin();

// ---- network. TCP handles are small ints >= 0; -1 = error.
bool hal_wifi_connect(const char* ssid, const char* pass, int timeout_ms);
const char* hal_wifi_ip();  // "" when not connected
int hal_tcp_connect(const char* host, int port, bool tls, int timeout_ms);
int hal_tcp_write(int h, const uint8_t* d, int n);              // bytes written or -1
int hal_tcp_read(int h, uint8_t* d, int n, int timeout_ms);     // bytes, 0 = timeout, -1 = closed
void hal_tcp_close(int h);
