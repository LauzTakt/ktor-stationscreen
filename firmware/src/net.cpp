// Minimal HTTP/1.1 GET + WebSocket (RFC 6455) client over hal_tcp_*.
#include "net.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "hal.h"
#include "ktvm.h"

static char g_err[96];
const char* net_last_error() { return g_err; }
static void set_err(const char* m) { snprintf(g_err, sizeof g_err, "%s", m); }

struct Url { bool tls; bool ws; char host[96]; int port; char path[256]; };

static bool parse_url(const char* u, Url* o) {
  memset(o, 0, sizeof *o);
  const char* p;
  if (!strncmp(u, "http://", 7)) p = u + 7;
  else if (!strncmp(u, "https://", 8)) { p = u + 8; o->tls = true; }
  else if (!strncmp(u, "ws://", 5)) { p = u + 5; o->ws = true; }
  else if (!strncmp(u, "wss://", 6)) { p = u + 6; o->ws = o->tls = true; }
  else { set_err("url must start with http://, https://, ws:// or wss://"); return false; }
  o->port = o->tls ? 443 : 80;
  size_t hl = strcspn(p, ":/?");
  if (hl == 0 || hl >= sizeof o->host) { set_err("bad host in url"); return false; }
  memcpy(o->host, p, hl);
  p += hl;
  if (*p == ':') { o->port = atoi(p + 1); p += 1 + strspn(p + 1, "0123456789"); }
  snprintf(o->path, sizeof o->path, "%s", *p ? p : "/");
  if (o->path[0] != '/') { memmove(o->path + 1, o->path, sizeof o->path - 1); o->path[0] = '/'; }
  return true;
}

static bool write_all(int h, const uint8_t* d, int n) {
  while (n > 0) {
    int w = hal_tcp_write(h, d, n);
    if (w <= 0) return false;
    d += w; n -= w;
  }
  return true;
}

// ---------------------------------------------------------------- growable buffer
struct Buf { uint8_t* d = nullptr; size_t n = 0, cap = 0; };
static bool buf_add(Buf* b, const uint8_t* s, size_t n) {
  if (b->n + n + 1 > b->cap) {
    size_t c = b->cap ? b->cap * 2 : 1024;
    while (c < b->n + n + 1) c *= 2;
    uint8_t* nd = (uint8_t*)realloc(b->d, c);
    if (!nd) return false;
    b->d = nd; b->cap = c;
  }
  memcpy(b->d + b->n, s, n);
  b->n += n;
  b->d[b->n] = 0;
  return true;
}
static void buf_drop(Buf* b, size_t n) {  // remove n bytes from the front
  memmove(b->d, b->d + n, b->n - n);
  b->n -= n;
  if (b->d) b->d[b->n] = 0;
}

static int find_crlf2(const Buf* b) {
  for (size_t i = 0; i + 3 < b->n; i++)
    if (!memcmp(b->d + i, "\r\n\r\n", 4)) return (int)i;
  return -1;
}

static const char* header_value(const char* hdrs, const char* name) {  // case-insensitive, returns ptr after ':'
  size_t nl = strlen(name);
  for (const char* l = hdrs; l && *l; l = strstr(l, "\r\n") ? strstr(l, "\r\n") + 2 : nullptr) {
    if (!strncasecmp(l, name, nl) && l[nl] == ':') {
      l += nl + 1;
      while (*l == ' ') l++;
      return l;
    }
  }
  return nullptr;
}

// Reads until the end of the headers. Returns header length (excluding the blank line) or -1.
static int read_headers(int h, Buf* b, int timeout_ms) {
  uint32_t t0 = hal_millis();
  uint8_t tmp[512];
  while (true) {
    int e = find_crlf2(b);
    if (e >= 0) return e;
    if (b->n > 8192) { set_err("response headers too large"); return -1; }
    if (vm_abort_flag || (int)(hal_millis() - t0) > timeout_ms) { set_err("timeout"); return -1; }
    int r = hal_tcp_read(h, tmp, sizeof tmp, 100);
    if (r < 0) { set_err("connection closed"); return -1; }
    if (r > 0 && !buf_add(b, tmp, r)) { set_err("out of memory"); return -1; }
  }
}

static int status_of(const Buf* b) {
  const char* sp = strchr((const char*)b->d, ' ');
  return sp ? atoi(sp + 1) : -1;
}

// ---------------------------------------------------------------- HTTP GET
char* net_http_get(const char* url, int timeout_ms, size_t* len, int* status) {
  *status = -1; *len = 0;
  Url u;
  if (!parse_url(url, &u)) return nullptr;
  int h = hal_tcp_connect(u.host, u.port, u.tls, timeout_ms);
  if (h < 0) { set_err("cannot connect"); return nullptr; }
  char req[512];
  int rl = snprintf(req, sizeof req,
                    "GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: ktvm\r\nAccept: */*\r\nConnection: close\r\n\r\n",
                    u.path, u.host);
  Buf b, body;
  char* out = nullptr;
  if (!write_all(h, (const uint8_t*)req, rl)) { set_err("write failed"); goto done; }
  {
    int he = read_headers(h, &b, timeout_ms);
    if (he < 0) goto done;
    *status = status_of(&b);
    b.d[he] = 0;
    const char* te = header_value((const char*)b.d, "Transfer-Encoding");
    bool chunked = te && !strncasecmp(te, "chunked", 7);
    const char* cl = header_value((const char*)b.d, "Content-Length");
    long want = cl ? atol(cl) : -1;
    buf_drop(&b, he + 4);
    // read the rest of the response (Connection: close) into b
    uint32_t t0 = hal_millis();
    uint8_t tmp[1024];
    while (want < 0 || (long)b.n < want) {
      if (b.n > NET_MAX_BODY + 4096) { set_err("response too large"); *status = -1; goto done; }
      if (vm_abort_flag || (int)(hal_millis() - t0) > timeout_ms) { set_err("timeout reading body"); *status = -1; goto done; }
      if (chunked && b.n >= 5 && !memcmp(b.d + b.n - 5, "0\r\n\r\n", 5)) break;
      int r = hal_tcp_read(h, tmp, sizeof tmp, 100);
      if (r < 0) break;
      if (r > 0 && !buf_add(&b, tmp, r)) { set_err("out of memory"); *status = -1; goto done; }
    }
    if (chunked) {
      size_t i = 0;
      while (i < b.n) {
        long n = strtol((const char*)b.d + i, nullptr, 16);
        const char* nl = strstr((const char*)b.d + i, "\r\n");
        if (!nl || n <= 0) break;
        i = (const uint8_t*)nl - b.d + 2;
        if (i + n > b.n) n = b.n - i;
        buf_add(&body, b.d + i, n);
        i += n + 2;
      }
    } else {
      buf_add(&body, b.d ? b.d : (const uint8_t*)"", b.n);
    }
    if (body.n > NET_MAX_BODY) { set_err("response too large"); *status = -1; goto done; }
    out = (char*)body.d;
    *len = body.n;
    body.d = nullptr;
    if (!out) { out = (char*)malloc(1); out[0] = 0; }
  }
done:
  hal_tcp_close(h);
  free(b.d);
  free(body.d);
  return out;
}

// ---------------------------------------------------------------- WebSocket
struct Ws { bool used; int h; Buf rx; Buf msg; bool open; size_t skip; bool skipping_msg; };
static Ws g_ws[NET_MAX_WS];

static bool ws_frame(Ws* w, int op, const uint8_t* d, size_t n) {
  uint8_t hdr[14];
  int hl = 0;
  hdr[hl++] = 0x80 | op;
  if (n < 126) hdr[hl++] = 0x80 | n;
  else if (n < 65536) { hdr[hl++] = 0x80 | 126; hdr[hl++] = n >> 8; hdr[hl++] = n & 0xFF; }
  else return false;
  uint32_t m = hal_random();
  uint8_t mask[4] = {(uint8_t)m, (uint8_t)(m >> 8), (uint8_t)(m >> 16), (uint8_t)(m >> 24)};
  memcpy(hdr + hl, mask, 4); hl += 4;
  if (!write_all(w->h, hdr, hl)) return false;
  uint8_t tmp[256];
  for (size_t off = 0; off < n;) {
    size_t k = n - off < sizeof tmp ? n - off : sizeof tmp;
    for (size_t i = 0; i < k; i++) tmp[i] = d[off + i] ^ mask[(off + i) & 3];
    if (!write_all(w->h, tmp, (int)k)) return false;
    off += k;
  }
  return true;
}

int net_ws_open(const char* url, int timeout_ms) {
  Url u;
  if (!parse_url(url, &u)) return -1;
  int slot = -1;
  for (int i = 0; i < NET_MAX_WS; i++) if (!g_ws[i].used) { slot = i; break; }
  if (slot < 0) { set_err("too many websockets"); return -1; }
  int h = hal_tcp_connect(u.host, u.port, u.tls, timeout_ms);
  if (h < 0) { set_err("cannot connect"); return -1; }
  char req[640];
  int rl = snprintf(req, sizeof req,
                    "GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    "Sec-WebSocket-Key: a3R2bS1rb3RsaW4tZXNwMw==\r\nSec-WebSocket-Version: 13\r\n\r\n",
                    u.path, u.host, u.port);
  Ws* w = &g_ws[slot];
  *w = Ws();
  w->h = h;
  if (!write_all(h, (const uint8_t*)req, rl)) { set_err("write failed"); hal_tcp_close(h); return -1; }
  int he = read_headers(h, &w->rx, timeout_ms);
  if (he < 0 || status_of(&w->rx) != 101) {
    if (he >= 0) set_err("server refused the websocket upgrade");
    hal_tcp_close(h); free(w->rx.d); *w = Ws();
    return -1;
  }
  buf_drop(&w->rx, he + 4);
  w->used = w->open = true;
  return slot;
}

static Ws* ws_get(int h) { return h >= 0 && h < NET_MAX_WS && g_ws[h].used ? &g_ws[h] : nullptr; }

// Try to take one complete frame out of w->rx. 1 = message complete, 0 = need more, -1 = closed.
static int ws_parse(Ws* w, char** msg, size_t* len) {
  while (true) {
    if (w->skip) {  // discarding the rest of an oversized frame
      size_t k = w->skip < w->rx.n ? w->skip : w->rx.n;
      buf_drop(&w->rx, k);
      w->skip -= k;
      if (w->skip) return 0;
    }
    if (w->rx.n < 2) return 0;
    uint8_t* d = w->rx.d;
    bool fin = d[0] & 0x80;
    int op = d[0] & 0x0F;
    bool masked = d[1] & 0x80;
    size_t n = d[1] & 0x7F, hl = 2;
    if (n == 126) { if (w->rx.n < 4) return 0; n = (d[2] << 8) | d[3]; hl = 4; }
    else if (n == 127) {
      if (w->rx.n < 10) return 0;
      n = 0;
      for (int i = 2; i < 10; i++) n = (n << 8) | d[i];
      hl = 10;
    }
    if (masked) hl += 4;
    if (n > NET_MAX_BODY || (op == 0x0 && w->skipping_msg)) {
      // Too big for the board: drop it (and its continuation frames) instead of breaking the connection.
      if (w->rx.n < hl) return 0;
      buf_drop(&w->rx, hl);
      w->skip = n;
      w->skipping_msg = !fin;
      w->msg.n = 0;
      hal_write("[net] websocket message too large, skipped\n", 43);
      continue;
    }
    if (w->rx.n < hl + n) return 0;
    uint8_t* p = d + hl;
    if (masked) for (size_t i = 0; i < n; i++) p[i] ^= d[hl - 4 + (i & 3)];
    if (op == 0x8) { ws_frame(w, 0x8, nullptr, 0); w->open = false; buf_drop(&w->rx, hl + n); return -1; }
    if (op == 0x9) { ws_frame(w, 0xA, p, n); buf_drop(&w->rx, hl + n); continue; }
    if (op == 0xA) { buf_drop(&w->rx, hl + n); continue; }
    if (op == 0x1 || op == 0x2 || op == 0x0) {
      if (op != 0x0) w->msg.n = 0;
      buf_add(&w->msg, p, n);
      buf_drop(&w->rx, hl + n);
      if (fin) {
        *msg = (char*)w->msg.d;
        *len = w->msg.n;
        w->msg = Buf();
        if (!*msg) { *msg = (char*)malloc(1); (*msg)[0] = 0; }
        return 1;
      }
      continue;
    }
    buf_drop(&w->rx, hl + n);  // unknown opcode: skip
  }
}

int net_ws_poll(int h, int timeout_ms, char** msg, size_t* len) {
  Ws* w = ws_get(h);
  if (!w) return -1;
  uint32_t t0 = hal_millis();
  uint8_t tmp[1024];
  while (true) {
    int p = ws_parse(w, msg, len);
    if (p) return p;
    if (!w->open) return -1;
    int left = timeout_ms - (int)(hal_millis() - t0);
    if (left <= 0 || vm_abort_flag) return 0;
    int r = hal_tcp_read(w->h, tmp, sizeof tmp, left < 50 ? left : 50);
    if (r < 0) { w->open = false; set_err("connection closed"); return -1; }
    if (r > 0 && !buf_add(&w->rx, tmp, r)) { set_err("out of memory"); return -1; }
  }
}

bool net_ws_send(int h, const char* s, size_t n) {
  Ws* w = ws_get(h);
  if (!w || !w->open) return false;
  if (!ws_frame(w, 0x1, (const uint8_t*)s, n)) { w->open = false; return false; }
  return true;
}

bool net_ws_connected(int h) { Ws* w = ws_get(h); return w && w->open; }

void net_ws_close(int h) {
  Ws* w = ws_get(h);
  if (!w) return;
  if (w->open) ws_frame(w, 0x8, nullptr, 0);
  hal_tcp_close(w->h);
  free(w->rx.d); free(w->msg.d);
  *w = Ws();
}

void net_reset() {
  for (int i = 0; i < NET_MAX_WS; i++) if (g_ws[i].used) net_ws_close(i);
}
