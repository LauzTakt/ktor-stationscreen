// HTTP/1.1 GET and WebSocket client on top of the hal_tcp_* functions (shared by the ESP32 and the simulator).
#pragma once
#include <stddef.h>
#include <stdint.h>

#define NET_MAX_BODY (8 * 1024)
#define NET_MAX_WS 4

// Returns a malloc'ed body (caller frees) or nullptr; *status = HTTP status (or -1 on network error).
// if_none_match: ETag of a previous response (or nullptr); an unchanged resource answers 304 with an empty body.
char* net_http_get(const char* url, int timeout_ms, size_t* len, int* status, const char* if_none_match = nullptr);
const char* net_last_etag();  // ETag header of the last net_http_get response ("" if none)
int net_ws_open(const char* url, int timeout_ms);  // handle or -1
// 1 = message in *msg (malloc'ed), 0 = nothing yet, -1 = closed
int net_ws_poll(int h, int timeout_ms, char** msg, size_t* len);
bool net_ws_send(int h, const char* s, size_t n);
bool net_ws_connected(int h);
void net_ws_close(int h);
const char* net_last_error();
void net_reset();  // close every websocket (called when a program ends)
