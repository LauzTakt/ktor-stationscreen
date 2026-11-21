// KTVM: loader, garbage-collected heap and JVM bytecode interpreter.
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "hal.h"
#include "ktvm_internal.h"
#include "natives_gen.h"

#ifdef ARDUINO
#define MAX_DEPTH 150
#else
#define MAX_DEPTH 3000
#endif
#define VSTACK_SLOTS 6144

Vm vm;
volatile bool vm_abort_flag = false;
static uint32_t poll_counter = 0;

// ======================================================================= output
void out_buf(const char* s, size_t n) { hal_write(s, n); }
void out_str(const char* s) { hal_write(s, strlen(s)); }

int vm_fatal(const char* fmt, ...) {
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof buf, fmt, ap);
  va_end(ap);
  out_str("\n[ktvm] error: ");
  out_str(buf);
  out_str("\n");
  return ST_FATAL;
}

static const char* sstr(uint16_t id, uint16_t* len) {
  if (id >= vm.nstrings) { *len = 1; return "?"; }
  *len = vm.str_len[id];
  return (const char*)vm.str_ptr[id];
}

static void out_sid(uint16_t id) {
  uint16_t n;
  const char* s = sstr(id, &n);
  out_buf(s, n);
}

// ======================================================================= heap
static inline uint32_t align8(uint32_t v) { return (v + 7) & ~7u; }
static inline bool sb_get(uint32_t off) { uint32_t g = off >> 3; return (vm.startbits[g >> 5] >> (g & 31)) & 1; }
static inline void sb_set(uint32_t off) { uint32_t g = off >> 3; vm.startbits[g >> 5] |= 1u << (g & 31); }
static inline void sb_clr(uint32_t off) { uint32_t g = off >> 3; vm.startbits[g >> 5] &= ~(1u << (g & 31)); }

uint32_t elem_size(uint8_t k) {
  switch (k) {
    case K_ARR_Z: case K_ARR_B: return 1;
    case K_ARR_C: case K_ARR_S: return 2;
    case K_ARR_J: case K_ARR_D: return 8;
    default: return 4;
  }
}
bool is_array_kind(uint8_t k) { return k >= K_ARR_Z && k <= K_ARR_REF; }

static uint32_t obj_bytes(const ObjH* h) {
  uint32_t payload;
  if (h->kind == K_FREE) return h->len;
  if (h->kind == K_OBJ) payload = h->len * 4;
  else if (h->kind == K_STR) payload = h->len + 1;
  else payload = h->len * elem_size(h->kind);
  return align8(sizeof(ObjH) + payload);
}

static inline bool is_obj(uint32_t v) {
  return v >= 8 && v < vm.heap_size && !(v & 7) && sb_get(v) && H(v)->kind != K_FREE;
}

#define MSTACK 256
static Ref mstack[MSTACK];
static int msp;
static bool moverflow;

static inline bool has_children(uint8_t k) { return k == K_OBJ || k == K_ARR_REF; }

static inline void mark_value(uint32_t v) {
  if (!is_obj(v)) return;
  ObjH* h = H(v);
  if (h->mark) return;
  h->mark = 1;
  if (has_children(h->kind)) {
    if (msp < MSTACK) mstack[msp++] = v;
    else moverflow = true;
  }
}

static void scan_children(Ref r) {
  ObjH* h = H(r);
  uint32_t* s = SLOTS(r);
  for (uint32_t i = 0; i < h->len; i++) mark_value(s[i]);
}

static void drain() {
  while (msp > 0) scan_children(mstack[--msp]);
}

void gc_collect() {
  msp = 0;
  moverflow = false;
  // roots
  for (uint32_t* p = vm.vstack; p < vm.top; p++) { mark_value(*p); drain(); }
  for (uint32_t i = 0; i < vm.nstatic; i++) { mark_value(vm.statics[i]); drain(); }
  for (uint32_t i = 0; i < vm.nstrings; i++) { if (vm.str_ref[i]) { mark_value(vm.str_ref[i]); drain(); } }
  mark_value(vm.exc);
  for (int i = 0; i < vm.ntmp; i++) mark_value(vm.tmp[i]);
  drain();
  while (moverflow) {
    moverflow = false;
    for (uint32_t off = 8; off < vm.heap_size; off += obj_bytes(H(off))) {
      ObjH* h = H(off);
      if (h->kind != K_FREE && h->mark && has_children(h->kind)) { scan_children(off); drain(); }
    }
  }
  // sweep + coalesce, rebuilding an address-ordered free list
  vm.free_list = 0;
  vm.heap_free = 0;
  Ref tail = 0, run = 0;
  uint32_t run_size = 0;
  auto flush = [&]() {
    if (!run) return;
    ObjH* h = H(run);
    h->kind = K_FREE; h->cls = 0; h->mark = 0; h->len = run_size;
    vm.heap_free += run_size;
    if (run_size >= 16) {
      SLOTS(run)[0] = 0;
      if (tail) SLOTS(tail)[0] = run; else vm.free_list = run;
      tail = run;
    }
    run = 0;
  };
  for (uint32_t off = 8; off < vm.heap_size;) {
    ObjH* h = H(off);
    uint32_t sz = obj_bytes(h);
    if (h->kind != K_FREE && h->mark) {
      h->mark = 0;
      flush();
    } else if (!run) {
      run = off; run_size = sz;
    } else {
      run_size += sz;
      sb_clr(off);
    }
    off += sz;
  }
  flush();
}

Ref gc_alloc(uint8_t kind, uint16_t cls, uint32_t len, uint32_t payload) {
  uint32_t need = align8(sizeof(ObjH) + payload);
  if (need < 8 || need >= vm.heap_size) return 0;
  for (int attempt = 0; attempt < 2; attempt++) {
    Ref prev = 0, r = vm.free_list;
    while (r) {
      ObjH* h = H(r);
      uint32_t bsz = h->len;
      Ref next = SLOTS(r)[0];
      if (bsz >= need) {
        uint32_t rem = bsz - need;
        Ref repl = next;
        if (rem >= 16) {
          Ref nr = r + need;
          ObjH* nh = H(nr);
          nh->kind = K_FREE; nh->cls = 0; nh->mark = 0; nh->len = rem;
          SLOTS(nr)[0] = next;
          sb_set(nr);
          repl = nr;
        } else if (rem == 8) {
          Ref nr = r + need;  // 8-byte filler, reclaimed by the next sweep
          ObjH* nh = H(nr);
          nh->kind = K_FREE; nh->cls = 0; nh->mark = 0; nh->len = 8;
          sb_set(nr);
        }
        if (prev) SLOTS(prev)[0] = repl; else vm.free_list = repl;
        h->cls = cls; h->kind = kind; h->mark = 0; h->len = len;
        memset(DATA(r), 0, need - sizeof(ObjH));
        vm.heap_free -= need;
        return r;
      }
      prev = r;
      r = next;
    }
    if (attempt == 0) gc_collect();
  }
  return 0;
}

uint32_t vm_heap_free() { return vm.heap_free; }

Ref new_string(const char* s, uint32_t n) {
  Ref r = gc_alloc(K_STR, CID_STRING, n, n + 1);
  if (!r) return 0;
  memcpy(DATA(r), s, n);
  DATA(r)[n] = 0;
  return r;
}

Ref new_array(uint8_t kind, uint32_t n) {
  if (n > 0x1000000) return 0;
  return gc_alloc(kind, CID_OBJECT, n, n * elem_size(kind));
}

const char* str_chars(Ref s) { return (const char*)DATA(s); }
uint32_t str_len(Ref s) { return H(s)->len; }

static Ref str_get(uint16_t id) {
  if (!vm.str_ref[id]) vm.str_ref[id] = new_string((const char*)vm.str_ptr[id], vm.str_len[id]);
  return vm.str_ref[id];
}

// ======================================================================= classes
bool instance_of(Ref r, uint16_t cid) {
  if (!r) return false;
  uint16_t c = H(r)->cls;
  if (c == cid) return true;
  if (c >= vm.nclasses) return false;
  const Cls& k = vm.classes[c];
  for (uint16_t i = 0; i < k.nsupers; i++)
    if (rd16(k.supers + 2 * i) == cid) return true;
  return false;
}

uint16_t vt_lookup(uint16_t cid, uint16_t sel) {
  const Cls& k = vm.classes[cid];
  int lo = 0, hi = (int)k.nvt - 1;
  while (lo <= hi) {
    int mid = (lo + hi) >> 1;
    uint16_t s = rd16(k.vt + 4 * mid);
    if (s == sel) return rd16(k.vt + 4 * mid + 2);
    if (s < sel) lo = mid + 1; else hi = mid - 1;
  }
  return NO_METHOD;
}

int throw_wk(int wk, const char* msg) {
  uint16_t cid = vm.wk[wk];
  if (cid >= vm.nclasses) return vm_fatal("%s (exception class not linked)", msg ? msg : "exception");
  Ref m = msg ? new_string(msg, strlen(msg)) : 0;
  if (m) push_tmp(m);
  Ref e = gc_alloc(K_OBJ, cid, vm.classes[cid].ninst, vm.classes[cid].ninst * 4);
  if (m) pop_tmp();
  if (!e) return vm_fatal("out of memory while throwing %s", msg ? msg : "exception");
  if (vm.classes[cid].ninst > 0) SLOTS(e)[0] = m;
  vm.exc = e;
  vm.ntrace = 0;
  return ST_EXC;
}

static int run_clinit(uint16_t cid, uint32_t* base);

static inline int ensure_init(uint16_t cid, uint32_t* base) {
  if (cid < vm.nclasses && !vm.classes[cid].init) return run_clinit(cid, base);
  return ST_OK;
}

static int run_clinit(uint16_t cid, uint32_t* base) {
  Cls& k = vm.classes[cid];
  k.init = 1;
  if (k.super_ < vm.nclasses) {
    int st = ensure_init(k.super_, base);
    if (st) return st;
  }
  if (k.clinit != NO_METHOD) return invoke_method(k.clinit, base);
  return ST_OK;
}

// ======================================================================= interpreter
static inline int16_t be16(const uint8_t* p) { return (int16_t)((p[0] << 8) | p[1]); }
static inline uint16_t beu16(const uint8_t* p) { return (uint16_t)((p[0] << 8) | p[1]); }
static inline int32_t be32(const uint8_t* p) {
  return (int32_t)(((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]);
}

static inline int32_t f2i(float f) {
  if (f != f) return 0;
  if (f >= 2147483647.0f) return INT32_MAX;
  if (f <= -2147483648.0f) return INT32_MIN;
  return (int32_t)f;
}
static inline int32_t d2i(double f) {
  if (f != f) return 0;
  if (f >= 2147483647.0) return INT32_MAX;
  if (f <= -2147483648.0) return INT32_MIN;
  return (int32_t)f;
}
static inline int64_t d2l(double f) {
  if (f != f) return 0;
  if (f >= 9223372036854775807.0) return INT64_MAX;
  if (f <= -9223372036854775808.0) return INT64_MIN;
  return (int64_t)f;
}

static __attribute__((noinline)) int unresolved(const CpE* e) {
  uint16_t n;
  const char* s = sstr((uint16_t)e->b, &n);
  char buf[160];
  snprintf(buf, sizeof buf, "%.*s is not available on the device", (int)n, s);
  return vm_fatal("%s", buf);
}

static inline uint8_t retslots_of(uint16_t target) {
  return (target & NATIVE_BIT) ? native_retslots[target & ~NATIVE_BIT] : vm.methods[target].retslots;
}

int invoke_target(uint16_t target, uint32_t* args) {
  if (target & NATIVE_BIT) return native_call(target & ~NATIVE_BIT, args);
  if (target == NO_METHOD) return vm_fatal("AbstractMethodError");
  return invoke_method(target, args);
}

static int interp(uint16_t mid, uint32_t* locals);

int invoke_method(uint16_t mid, uint32_t* args) {
  const Mth* m = &vm.methods[mid];
  uint32_t* frame_end = args + m->max_locals + m->max_stack;
  if (frame_end > vm.vstack + vm.vstack_slots || vm.depth >= MAX_DEPTH)
    return throw_wk(WK_SOE, "stack overflow");
  if (vm_abort_flag) return ST_ABORT;
  if (m->max_locals > m->argslots) memset(args + m->argslots, 0, (m->max_locals - m->argslots) * 4);
  uint32_t* saved_top = vm.top;
  if (frame_end > vm.top) vm.top = frame_end;
  vm.depth++;
  int st = interp(mid, args);
  vm.depth--;
  vm.top = saved_top;
  return st;
}

static Ref multi_array(const char* desc, const int32_t* counts, int dims) {
  // desc points at the '[' for the current level
  int32_t n = counts[0];
  if (dims == 1) {
    char c = desc[1];
    uint8_t k;
    switch (c) {
      case 'Z': k = K_ARR_Z; break;
      case 'B': k = K_ARR_B; break;
      case 'C': k = K_ARR_C; break;
      case 'S': k = K_ARR_S; break;
      case 'I': k = K_ARR_I; break;
      case 'F': k = K_ARR_F; break;
      case 'J': k = K_ARR_J; break;
      case 'D': k = K_ARR_D; break;
      default: k = K_ARR_REF;
    }
    return new_array(k, n);
  }
  Ref a = new_array(K_ARR_REF, n);
  if (!a) return 0;
  push_tmp(a);
  for (int32_t i = 0; i < n; i++) {
    Ref sub = multi_array(desc + 1, counts + 1, dims - 1);
    if (!sub) { pop_tmp(); return 0; }
    SLOTS(a)[i] = sub;
  }
  pop_tmp();
  return a;
}

#define NOINLINE __attribute__((noinline))
static NOINLINE int throw_aioobe(int32_t i, uint32_t len) {
  char b[64];
  snprintf(b, sizeof b, "Index %d out of bounds for length %u", (int)i, (unsigned)len);
  return throw_wk(WK_AIOOBE, b);
}
static NOINLINE int throw_cce(Ref r, uint16_t cid) {
  char b[128];
  uint16_t n1, n2;
  const char* s1 = is_array_kind(H(r)->kind) ? (n1 = 5, "array") : sstr(vm.classes[H(r)->cls].name, &n1);
  const char* s2 = cid < vm.nclasses ? sstr(vm.classes[cid].name, &n2) : (n2 = 5, "array");
  snprintf(b, sizeof b, "%.*s cannot be cast to %.*s", (int)n1, s1, (int)n2, s2);
  return throw_wk(WK_CCE, b);
}
static NOINLINE int abstract_error(Ref recv, uint32_t sel) {
  uint16_t n;
  const char* s = sstr(vm.classes[H(recv)->cls].name, &n);
  return vm_fatal("AbstractMethodError: selector %u not implemented by %.*s", (unsigned)sel, (int)n, s);
}
static NOINLINE int unknown_class(const CpE* e) {
  uint16_t n;
  const char* s = sstr((uint16_t)e->b, &n);
  return vm_fatal("class %.*s is not available on the device", (int)n, s);
}

#define U16() (beu16(pc))
#define PUSH(v) (*sp++ = (uint32_t)(v))
#define POP() (*--sp)
#define IPOP() ((int32_t)(*--sp))
#define FPOP() (i2f_bits(*--sp))
#define FPUSH(x) PUSH(f2i_bits(x))
#define LPOP() (sp -= 2, getL(sp))
#define LPUSH(x) (putL(sp, (x)), sp += 2)
#define DPOP() (sp -= 2, getD(sp))
#define DPUSH(x) (putD(sp, (x)), sp += 2)
#define CHECK(expr) do { int _st = (expr); if (_st) { if (_st == ST_EXC) goto handle; return _st; } } while (0)
#define THROW(wk, msg) do { CHECK(throw_wk(wk, msg)); } while (0)
#define BRANCH(cond) do { if (cond) { int16_t _o = be16(pc); pc = ip + _o; if (_o <= 0) BACKEDGE(); } else pc += 2; } while (0)
#define BACKEDGE() do { if (vm_abort_flag) return ST_ABORT; if (!(++poll_counter & 0x3fff)) hal_poll(); } while (0)
#define ARR(r, i) do { if (!(r)) THROW(WK_NPE, "array is null"); if ((uint32_t)(i) >= H(r)->len) CHECK(throw_aioobe((i), H(r)->len)); } while (0)

static int interp(uint16_t mid, uint32_t* locals) {
  const Mth* m = &vm.methods[mid];
  const uint8_t* code = m->code;
  const uint8_t* pc = code;
  const uint8_t* ip = code;
  CpE* cp = vm.classes[m->cls].cp;
  uint32_t* stack_base = locals + m->max_locals;
  uint32_t* sp = stack_base;

  for (;;) {
    ip = pc;
    uint8_t op = *pc++;
    switch (op) {
      case 0x00: break;
      case 0x01: PUSH(0); break;
      case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08: PUSH((int32_t)op - 3); break;
      case 0x09: LPUSH(0); break;
      case 0x0a: LPUSH(1); break;
      case 0x0b: FPUSH(0.0f); break;
      case 0x0c: FPUSH(1.0f); break;
      case 0x0d: FPUSH(2.0f); break;
      case 0x0e: DPUSH(0.0); break;
      case 0x0f: DPUSH(1.0); break;
      case 0x10: PUSH((int32_t)(int8_t)*pc++); break;
      case 0x11: PUSH((int32_t)be16(pc)); pc += 2; break;
      case 0x12: case 0x13: {  // ldc, ldc_w
        uint16_t idx = (op == 0x12) ? *pc++ : (pc += 2, beu16(pc - 2));
        const CpE* e = &cp[idx];
        if (e->tag == CP_INT) PUSH(e->b);
        else if (e->tag == CP_STR) {
          Ref s = str_get(e->a);
          if (!s) THROW(WK_OOM, "out of memory");
          PUSH(s);
        } else return vm_fatal("ldc of unsupported constant (class literals / ::class are not supported)");
        break;
      }
      case 0x14: { uint16_t idx = U16(); pc += 2; PUSH(cp[idx].b); PUSH(cp[idx + 1].b); break; }
      // loads
      case 0x15: case 0x17: case 0x19: PUSH(locals[*pc++]); break;
      case 0x16: case 0x18: { uint8_t i = *pc++; PUSH(locals[i]); PUSH(locals[i + 1]); break; }
      case 0x1a: case 0x1b: case 0x1c: case 0x1d: PUSH(locals[op - 0x1a]); break;
      case 0x1e: case 0x1f: case 0x20: case 0x21: { int i = op - 0x1e; PUSH(locals[i]); PUSH(locals[i + 1]); break; }
      case 0x22: case 0x23: case 0x24: case 0x25: PUSH(locals[op - 0x22]); break;
      case 0x26: case 0x27: case 0x28: case 0x29: { int i = op - 0x26; PUSH(locals[i]); PUSH(locals[i + 1]); break; }
      case 0x2a: case 0x2b: case 0x2c: case 0x2d: PUSH(locals[op - 0x2a]); break;
      // array loads
      case 0x2e: case 0x30: case 0x32: { int32_t i = IPOP(); Ref a = POP(); ARR(a, i); PUSH(((uint32_t*)DATA(a))[i]); break; }
      case 0x2f: case 0x31: { int32_t i = IPOP(); Ref a = POP(); ARR(a, i); memcpy(sp, DATA(a) + 8 * i, 8); sp += 2; break; }
      case 0x33: { int32_t i = IPOP(); Ref a = POP(); ARR(a, i); PUSH((int32_t)((int8_t*)DATA(a))[i]); break; }
      case 0x34: { int32_t i = IPOP(); Ref a = POP(); ARR(a, i); PUSH((int32_t)((uint16_t*)DATA(a))[i]); break; }
      case 0x35: { int32_t i = IPOP(); Ref a = POP(); ARR(a, i); PUSH((int32_t)((int16_t*)DATA(a))[i]); break; }
      // stores
      case 0x36: case 0x38: case 0x3a: locals[*pc++] = POP(); break;
      case 0x37: case 0x39: { uint8_t i = *pc++; locals[i + 1] = POP(); locals[i] = POP(); break; }
      case 0x3b: case 0x3c: case 0x3d: case 0x3e: locals[op - 0x3b] = POP(); break;
      case 0x3f: case 0x40: case 0x41: case 0x42: { int i = op - 0x3f; locals[i + 1] = POP(); locals[i] = POP(); break; }
      case 0x43: case 0x44: case 0x45: case 0x46: locals[op - 0x43] = POP(); break;
      case 0x47: case 0x48: case 0x49: case 0x4a: { int i = op - 0x47; locals[i + 1] = POP(); locals[i] = POP(); break; }
      case 0x4b: case 0x4c: case 0x4d: case 0x4e: locals[op - 0x4b] = POP(); break;
      // array stores
      case 0x4f: case 0x51: case 0x53: { uint32_t v = POP(); int32_t i = IPOP(); Ref a = POP(); ARR(a, i); ((uint32_t*)DATA(a))[i] = v; break; }
      case 0x50: case 0x52: { sp -= 2; uint32_t* v = sp; int32_t i = IPOP(); Ref a = POP(); ARR(a, i); memcpy(DATA(a) + 8 * i, v, 8); break; }
      case 0x54: { int32_t v = IPOP(); int32_t i = IPOP(); Ref a = POP(); ARR(a, i); ((int8_t*)DATA(a))[i] = (int8_t)(H(a)->kind == K_ARR_Z ? (v & 1) : v); break; }
      case 0x55: case 0x56: { int32_t v = IPOP(); int32_t i = IPOP(); Ref a = POP(); ARR(a, i); ((uint16_t*)DATA(a))[i] = (uint16_t)v; break; }
      // stack
      case 0x57: sp--; break;
      case 0x58: sp -= 2; break;
      case 0x59: sp[0] = sp[-1]; sp++; break;
      case 0x5a: { uint32_t v1 = sp[-1], v2 = sp[-2]; sp[-2] = v1; sp[-1] = v2; sp[0] = v1; sp++; break; }
      case 0x5b: { uint32_t a = sp[-3], b = sp[-2], c = sp[-1]; sp[-3] = c; sp[-2] = a; sp[-1] = b; sp[0] = c; sp++; break; }
      case 0x5c: sp[0] = sp[-2]; sp[1] = sp[-1]; sp += 2; break;
      case 0x5d: { uint32_t a = sp[-3], b = sp[-2], c = sp[-1]; sp[-3] = b; sp[-2] = c; sp[-1] = a; sp[0] = b; sp[1] = c; sp += 2; break; }
      case 0x5e: { uint32_t a = sp[-4], b = sp[-3], c = sp[-2], d = sp[-1];
                   sp[-4] = c; sp[-3] = d; sp[-2] = a; sp[-1] = b; sp[0] = c; sp[1] = d; sp += 2; break; }
      case 0x5f: { uint32_t t = sp[-1]; sp[-1] = sp[-2]; sp[-2] = t; break; }
      // arithmetic
      case 0x60: { uint32_t b = POP(), a = POP(); PUSH(a + b); break; }
      case 0x61: { int64_t b = LPOP(), a = LPOP(); LPUSH((int64_t)((uint64_t)a + (uint64_t)b)); break; }
      case 0x62: { float b = FPOP(), a = FPOP(); FPUSH(a + b); break; }
      case 0x63: { double b = DPOP(), a = DPOP(); DPUSH(a + b); break; }
      case 0x64: { uint32_t b = POP(), a = POP(); PUSH(a - b); break; }
      case 0x65: { int64_t b = LPOP(), a = LPOP(); LPUSH((int64_t)((uint64_t)a - (uint64_t)b)); break; }
      case 0x66: { float b = FPOP(), a = FPOP(); FPUSH(a - b); break; }
      case 0x67: { double b = DPOP(), a = DPOP(); DPUSH(a - b); break; }
      case 0x68: { uint32_t b = POP(), a = POP(); PUSH(a * b); break; }
      case 0x69: { int64_t b = LPOP(), a = LPOP(); LPUSH((int64_t)((uint64_t)a * (uint64_t)b)); break; }
      case 0x6a: { float b = FPOP(), a = FPOP(); FPUSH(a * b); break; }
      case 0x6b: { double b = DPOP(), a = DPOP(); DPUSH(a * b); break; }
      case 0x6c: { int32_t b = IPOP(), a = IPOP(); if (!b) THROW(WK_ARITH, "/ by zero");
                   PUSH((a == INT32_MIN && b == -1) ? a : a / b); break; }
      case 0x6d: { int64_t b = LPOP(), a = LPOP(); if (!b) THROW(WK_ARITH, "/ by zero");
                   LPUSH((a == INT64_MIN && b == -1) ? a : a / b); break; }
      case 0x6e: { float b = FPOP(), a = FPOP(); FPUSH(a / b); break; }
      case 0x6f: { double b = DPOP(), a = DPOP(); DPUSH(a / b); break; }
      case 0x70: { int32_t b = IPOP(), a = IPOP(); if (!b) THROW(WK_ARITH, "/ by zero");
                   PUSH(b == -1 ? 0 : a % b); break; }
      case 0x71: { int64_t b = LPOP(), a = LPOP(); if (!b) THROW(WK_ARITH, "/ by zero");
                   LPUSH(b == -1 ? 0 : a % b); break; }
      case 0x72: { float b = FPOP(), a = FPOP(); FPUSH(fmodf(a, b)); break; }
      case 0x73: { double b = DPOP(), a = DPOP(); DPUSH(fmod(a, b)); break; }
      case 0x74: { uint32_t a = POP(); PUSH(0u - a); break; }
      case 0x75: { int64_t a = LPOP(); LPUSH((int64_t)(0ull - (uint64_t)a)); break; }
      case 0x76: { float a = FPOP(); FPUSH(-a); break; }
      case 0x77: { double a = DPOP(); DPUSH(-a); break; }
      case 0x78: { int32_t s = IPOP(); uint32_t a = POP(); PUSH(a << (s & 31)); break; }
      case 0x79: { int32_t s = IPOP(); int64_t a = LPOP(); LPUSH((int64_t)((uint64_t)a << (s & 63))); break; }
      case 0x7a: { int32_t s = IPOP(); int32_t a = IPOP(); PUSH(a >> (s & 31)); break; }
      case 0x7b: { int32_t s = IPOP(); int64_t a = LPOP(); LPUSH(a >> (s & 63)); break; }
      case 0x7c: { int32_t s = IPOP(); uint32_t a = POP(); PUSH(a >> (s & 31)); break; }
      case 0x7d: { int32_t s = IPOP(); int64_t a = LPOP(); LPUSH((int64_t)((uint64_t)a >> (s & 63))); break; }
      case 0x7e: { uint32_t b = POP(), a = POP(); PUSH(a & b); break; }
      case 0x7f: { int64_t b = LPOP(), a = LPOP(); LPUSH(a & b); break; }
      case 0x80: { uint32_t b = POP(), a = POP(); PUSH(a | b); break; }
      case 0x81: { int64_t b = LPOP(), a = LPOP(); LPUSH(a | b); break; }
      case 0x82: { uint32_t b = POP(), a = POP(); PUSH(a ^ b); break; }
      case 0x83: { int64_t b = LPOP(), a = LPOP(); LPUSH(a ^ b); break; }
      case 0x84: { uint8_t i = pc[0]; locals[i] += (int32_t)(int8_t)pc[1]; pc += 2; break; }
      // conversions
      case 0x85: { int32_t a = IPOP(); LPUSH((int64_t)a); break; }
      case 0x86: { int32_t a = IPOP(); FPUSH((float)a); break; }
      case 0x87: { int32_t a = IPOP(); DPUSH((double)a); break; }
      case 0x88: { int64_t a = LPOP(); PUSH((uint32_t)a); break; }
      case 0x89: { int64_t a = LPOP(); FPUSH((float)a); break; }
      case 0x8a: { int64_t a = LPOP(); DPUSH((double)a); break; }
      case 0x8b: { float a = FPOP(); PUSH(f2i(a)); break; }
      case 0x8c: { float a = FPOP(); LPUSH(d2l(a)); break; }
      case 0x8d: { float a = FPOP(); DPUSH((double)a); break; }
      case 0x8e: { double a = DPOP(); PUSH(d2i(a)); break; }
      case 0x8f: { double a = DPOP(); LPUSH(d2l(a)); break; }
      case 0x90: { double a = DPOP(); FPUSH((float)a); break; }
      case 0x91: { int32_t a = IPOP(); PUSH((int32_t)(int8_t)a); break; }
      case 0x92: { int32_t a = IPOP(); PUSH((int32_t)(uint16_t)a); break; }
      case 0x93: { int32_t a = IPOP(); PUSH((int32_t)(int16_t)a); break; }
      // comparisons
      case 0x94: { int64_t b = LPOP(), a = LPOP(); PUSH(a < b ? -1 : (a > b ? 1 : 0)); break; }
      case 0x95: case 0x96: { float b = FPOP(), a = FPOP();
                   PUSH(a < b ? -1 : a > b ? 1 : a == b ? 0 : (op == 0x95 ? -1 : 1)); break; }
      case 0x97: case 0x98: { double b = DPOP(), a = DPOP();
                   PUSH(a < b ? -1 : a > b ? 1 : a == b ? 0 : (op == 0x97 ? -1 : 1)); break; }
      case 0x99: { int32_t a = IPOP(); BRANCH(a == 0); break; }
      case 0x9a: { int32_t a = IPOP(); BRANCH(a != 0); break; }
      case 0x9b: { int32_t a = IPOP(); BRANCH(a < 0); break; }
      case 0x9c: { int32_t a = IPOP(); BRANCH(a >= 0); break; }
      case 0x9d: { int32_t a = IPOP(); BRANCH(a > 0); break; }
      case 0x9e: { int32_t a = IPOP(); BRANCH(a <= 0); break; }
      case 0x9f: { int32_t b = IPOP(), a = IPOP(); BRANCH(a == b); break; }
      case 0xa0: { int32_t b = IPOP(), a = IPOP(); BRANCH(a != b); break; }
      case 0xa1: { int32_t b = IPOP(), a = IPOP(); BRANCH(a < b); break; }
      case 0xa2: { int32_t b = IPOP(), a = IPOP(); BRANCH(a >= b); break; }
      case 0xa3: { int32_t b = IPOP(), a = IPOP(); BRANCH(a > b); break; }
      case 0xa4: { int32_t b = IPOP(), a = IPOP(); BRANCH(a <= b); break; }
      case 0xa5: { uint32_t b = POP(), a = POP(); BRANCH(a == b); break; }
      case 0xa6: { uint32_t b = POP(), a = POP(); BRANCH(a != b); break; }
      case 0xa7: BRANCH(true); break;
      case 0xa8: case 0xa9: case 0xc9: return vm_fatal("jsr/ret are not supported");
      case 0xaa: {  // tableswitch
        const uint8_t* p = code + (((ip - code) + 4) & ~3);
        int32_t def = be32(p), lo = be32(p + 4), hi = be32(p + 8);
        int32_t key = IPOP();
        int32_t off = (key < lo || key > hi) ? def : be32(p + 12 + 4 * (key - lo));
        pc = ip + off;
        if (off <= 0) BACKEDGE();
        break;
      }
      case 0xab: {  // lookupswitch
        const uint8_t* p = code + (((ip - code) + 4) & ~3);
        int32_t def = be32(p), n = be32(p + 4);
        int32_t key = IPOP();
        int32_t off = def;
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
          int mdl = (lo + hi) >> 1;
          int32_t k = be32(p + 8 + 8 * mdl);
          if (k == key) { off = be32(p + 12 + 8 * mdl); break; }
          if (k < key) lo = mdl + 1; else hi = mdl - 1;
        }
        pc = ip + off;
        if (off <= 0) BACKEDGE();
        break;
      }
      case 0xac: case 0xae: case 0xb0: locals[0] = sp[-1]; return ST_OK;
      case 0xad: case 0xaf: locals[0] = sp[-2]; locals[1] = sp[-1]; return ST_OK;
      case 0xb1: return ST_OK;
      // fields
      case 0xb2: case 0xb3: {
        const CpE* e = &cp[U16()]; pc += 2;
        if (e->tag != CP_FIELD_S) return e->tag == CP_UNRESOLVED ? unresolved(e) : vm_fatal("bad static field ref");
        CHECK(ensure_init((uint16_t)e->b, sp));
        uint32_t* f = &vm.statics[e->a];
        if (op == 0xb2) { PUSH(f[0]); if (e->aux == 2) PUSH(f[1]); }
        else { if (e->aux == 2) f[1] = POP(); f[0] = POP(); }
        break;
      }
      case 0xb4: {
        const CpE* e = &cp[U16()]; pc += 2;
        if (e->tag != CP_FIELD_I) return e->tag == CP_UNRESOLVED ? unresolved(e) : vm_fatal("bad field ref");
        Ref o = POP();
        if (!o) THROW(WK_NPE, "null object (field read)");
        uint32_t* f = SLOTS(o) + e->a;
        PUSH(f[0]);
        if (e->aux == 2) PUSH(f[1]);
        break;
      }
      case 0xb5: {
        const CpE* e = &cp[U16()]; pc += 2;
        if (e->tag != CP_FIELD_I) return e->tag == CP_UNRESOLVED ? unresolved(e) : vm_fatal("bad field ref");
        uint32_t v1 = 0, v0 = POP();
        if (e->aux == 2) { v1 = v0; v0 = POP(); }
        Ref o = POP();
        if (!o) THROW(WK_NPE, "null object (field write)");
        uint32_t* f = SLOTS(o) + e->a;
        f[0] = v0;
        if (e->aux == 2) f[1] = v1;
        break;
      }
      // invokes
      case 0xb6: case 0xb7: case 0xb8: case 0xb9: {
        const CpE* e = &cp[U16()];
        pc += (op == 0xb9) ? 4 : 2;
        if (e->tag != CP_METHOD && e->tag != CP_METHOD_V)
          return e->tag == CP_UNRESOLVED ? unresolved(e) : vm_fatal("bad method ref");
        uint32_t nargs = e->aux + (op == 0xb8 ? 0 : 1);
        uint32_t* args = sp - nargs;
        uint16_t target = e->a;
        if (op == 0xb8) {
          if (!(target & NATIVE_BIT) && target != NO_METHOD) CHECK(ensure_init(vm.methods[target].cls, sp));
        } else {
          Ref recv = args[0];
          if (!recv) THROW(WK_NPE, "null receiver in method call");
          if (op != 0xb7 && e->tag == CP_METHOD_V) {
            target = vt_lookup(H(recv)->cls, (uint16_t)e->b);
            if (target == NO_METHOD) return abstract_error(recv, e->b);
          }
        }
        CHECK(invoke_target(target, args));
        sp = args + retslots_of(target);
        break;
      }
      case 0xba: return vm_fatal("invokedynamic is not supported (compile with -Xlambdas=class)");
      // objects
      case 0xbb: {
        const CpE* e = &cp[U16()]; pc += 2;
        uint16_t cid = e->a;
        if (cid >= vm.nclasses) return unknown_class(e);
        if (cid == CID_STRING) return vm_fatal("`new String(...)` is not supported");
        CHECK(ensure_init(cid, sp));
        const Cls& k = vm.classes[cid];
        Ref o = gc_alloc(K_OBJ, cid, k.ninst, k.ninst * 4);
        if (!o) THROW(WK_OOM, "out of memory");
        PUSH(o);
        break;
      }
      case 0xbc: {
        uint8_t t = *pc++;
        int32_t n = IPOP();
        if (n < 0) THROW(WK_NEGSIZE, "negative array size");
        static const uint8_t kinds[] = {0, 0, 0, 0, K_ARR_Z, K_ARR_C, K_ARR_F, K_ARR_D, K_ARR_B, K_ARR_S, K_ARR_I, K_ARR_J};
        Ref a = new_array(t < 12 ? kinds[t] : K_ARR_I, (uint32_t)n);
        if (!a) THROW(WK_OOM, "out of memory");
        PUSH(a);
        break;
      }
      case 0xbd: {
        pc += 2;
        int32_t n = IPOP();
        if (n < 0) THROW(WK_NEGSIZE, "negative array size");
        Ref a = new_array(K_ARR_REF, (uint32_t)n);
        if (!a) THROW(WK_OOM, "out of memory");
        PUSH(a);
        break;
      }
      case 0xbe: { Ref a = POP(); if (!a) THROW(WK_NPE, "array is null"); PUSH(H(a)->len); break; }
      case 0xbf: {
        Ref ex = POP();
        if (!ex) THROW(WK_NPE, "throw null");
        vm.exc = ex;
        vm.ntrace = 0;
        goto handle;
      }
      case 0xc0: case 0xc1: {
        const CpE* e = &cp[U16()]; pc += 2;
        Ref r = (op == 0xc0) ? sp[-1] : POP();
        bool ok;
        if (!r) ok = (op == 0xc0);
        else if (e->a == CID_UNKNOWN) ok = (op == 0xc0);
        else if (e->a == CID_ARRAY) ok = is_array_kind(H(r)->kind);
        else ok = instance_of(r, e->a);
        if (op == 0xc1) PUSH(ok ? 1 : 0);
        else if (!ok) CHECK(throw_cce(r, e->a));
        break;
      }
      case 0xc2: case 0xc3: sp--; break;  // monitors: single-threaded
      case 0xc4: {  // wide
        uint8_t op2 = *pc++;
        uint16_t i = U16(); pc += 2;
        switch (op2) {
          case 0x15: case 0x17: case 0x19: PUSH(locals[i]); break;
          case 0x16: case 0x18: PUSH(locals[i]); PUSH(locals[i + 1]); break;
          case 0x36: case 0x38: case 0x3a: locals[i] = POP(); break;
          case 0x37: case 0x39: locals[i + 1] = POP(); locals[i] = POP(); break;
          case 0x84: locals[i] += (int32_t)be16(pc); pc += 2; break;
          default: return vm_fatal("bad wide opcode %02x", op2);
        }
        break;
      }
      case 0xc5: {
        const CpE* e = &cp[U16()];
        uint8_t dims = pc[2];
        pc += 3;
        int32_t counts[4];
        if (dims > 4) return vm_fatal("too many array dimensions");
        for (int d = dims - 1; d >= 0; d--) {
          counts[d] = IPOP();
          if (counts[d] < 0) THROW(WK_NEGSIZE, "negative array size");
        }
        uint16_t n;
        const char* desc = sstr((uint16_t)e->b, &n);
        Ref a = multi_array(desc, counts, dims);
        if (!a) THROW(WK_OOM, "out of memory");
        PUSH(a);
        break;
      }
      case 0xc6: { Ref a = POP(); BRANCH(a == 0); break; }
      case 0xc7: { Ref a = POP(); BRANCH(a != 0); break; }
      case 0xc8: { int32_t o = be32(pc); pc = ip + o; if (o <= 0) BACKEDGE(); break; }
      default: return vm_fatal("unknown opcode 0x%02x", op);
    }
    continue;

  handle: {
      uint32_t off = (uint32_t)(ip - code);
      const uint8_t* e = m->exc;
      for (uint16_t k = 0; k < m->nexc; k++, e += 8) {
        uint16_t s = rd16(e), en = rd16(e + 2), h = rd16(e + 4), cid = rd16(e + 6);
        if (off >= s && off < en && (cid == CID_ANY || (cid < vm.nclasses && instance_of(vm.exc, cid)))) {
          sp = stack_base;
          PUSH(vm.exc);
          vm.exc = 0;
          vm.ntrace = 0;
          pc = code + h;
          goto next;
        }
      }
      if (vm.ntrace < 8) { vm.trace_mid[vm.ntrace] = mid; vm.trace_pc[vm.ntrace] = (uint16_t)off; vm.ntrace++; }
      return ST_EXC;
    }
  next:;
  }
}

// ======================================================================= loader
struct Reader {
  const uint8_t* p;
  const uint8_t* end;
  bool bad;
  uint8_t u8() { if (p + 1 > end) { bad = true; return 0; } return *p++; }
  uint16_t u16() { if (p + 2 > end) { bad = true; return 0; } uint16_t v = rd16(p); p += 2; return v; }
  uint32_t u32() { if (p + 4 > end) { bad = true; return 0; } uint32_t v = rd32(p); p += 4; return v; }
  const uint8_t* take(size_t n) { if (p + n > end) { bad = true; return end; } const uint8_t* r = p; p += n; return r; }
};

void vm_unload() {
  if (vm.classes) for (uint16_t i = 0; i < vm.nclasses; i++) free(vm.classes[i].cp);
  free(vm.classes); free(vm.methods); free(vm.statics); free(vm.str_ptr); free(vm.str_len);
  free(vm.str_ref); free(vm.heap); free(vm.startbits); free(vm.vstack);
  memset(&vm, 0, sizeof vm);
}

bool vm_load(const uint8_t* img, size_t len, uint32_t heap_bytes, char* err, size_t errlen) {
  vm_unload();
  Reader r{img, img + len, false};
#define FAIL(msg) do { snprintf(err, errlen, "%s", msg); vm_unload(); return false; } while (0)
  if (len < 4 || memcmp(r.take(4), "KTV1", 4)) FAIL("bad image magic");
  if (r.u16() != 1) FAIL("unsupported image version");
  vm.img = img;
  vm.nstrings = r.u16(); vm.nclasses = r.u16(); vm.nmethods = r.u16();
  vm.nstatic = r.u16(); vm.entry = r.u16(); vm.entry_args = r.u8();
  uint8_t nwk = r.u8();
  for (int i = 0; i < nwk; i++) { uint16_t c = r.u16(); if (i < WK_COUNT_MAX) vm.wk[i] = c; }
  for (int i = nwk; i < WK_COUNT_MAX; i++) vm.wk[i] = 0xFFFF;
  uint16_t nsinit = r.u16();
  const uint8_t* sinit = r.p;
  for (uint16_t i = 0; i < nsinit; i++) { r.u16(); uint8_t k = r.u8(); r.u32(); if (k == 1) r.u32(); }

  vm.str_ptr = (const uint8_t**)calloc(vm.nstrings + 1, sizeof(uint8_t*));
  vm.str_len = (uint16_t*)calloc(vm.nstrings + 1, sizeof(uint16_t));
  vm.str_ref = (Ref*)calloc(vm.nstrings + 1, sizeof(Ref));
  vm.classes = (Cls*)calloc(vm.nclasses + 1, sizeof(Cls));
  vm.methods = (Mth*)calloc(vm.nmethods + 1, sizeof(Mth));
  vm.statics = (uint32_t*)calloc(vm.nstatic + 1, 4);
  if (!vm.str_ptr || !vm.str_len || !vm.str_ref || !vm.classes || !vm.methods || !vm.statics) FAIL("out of memory (tables)");

  for (uint16_t i = 0; i < vm.nstrings; i++) {
    vm.str_len[i] = r.u16();
    vm.str_ptr[i] = r.take(vm.str_len[i]);
  }
  for (uint16_t i = 0; i < vm.nclasses; i++) {
    Cls& k = vm.classes[i];
    k.name = r.u16(); k.src = r.u16(); k.super_ = r.u16(); k.ninst = r.u16(); k.clinit = r.u16();
    k.flags = r.u8();
    k.nsupers = r.u16(); k.supers = r.take(2 * k.nsupers);
    k.nvt = r.u16(); k.vt = r.take(4 * k.nvt);
    k.ncp = r.u16();
    k.cp = (CpE*)calloc(k.ncp + 1, sizeof(CpE));
    if (!k.cp) FAIL("out of memory (constant pool)");
    for (uint16_t j = 0; j < k.ncp; j++) {
      k.cp[j].tag = r.u8(); k.cp[j].aux = r.u8(); k.cp[j].a = r.u16(); k.cp[j].b = r.u32();
    }
    if (r.bad) FAIL("truncated image (classes)");
  }
  for (uint16_t i = 0; i < vm.nmethods; i++) {
    Mth& m = vm.methods[i];
    m.cls = r.u16(); m.name = r.u16(); m.flags = r.u8();
    m.max_stack = r.u16(); m.max_locals = r.u16(); m.argslots = r.u16(); m.retslots = r.u8();
    r.u16();  // reserved
    m.code_len = r.u32(); m.code = r.take(m.code_len);
    m.nexc = r.u16(); m.exc = r.take(8 * m.nexc);
    m.nlines = r.u16(); m.lines = r.take(4 * m.nlines);
    if (r.bad) FAIL("truncated image (methods)");
    if (m.cls >= vm.nclasses) FAIL("corrupt image (method class)");
  }
  if (vm.entry >= vm.nmethods) FAIL("corrupt image (entry)");

  // stack first, then the GC heap gets (most of) the largest remaining block
  vm.vstack_slots = VSTACK_SLOTS;
  vm.vstack = (uint32_t*)calloc(vm.vstack_slots, 4);
  if (!vm.vstack) FAIL("out of memory (stack)");
  if (heap_bytes == 0) {
    uint32_t big = hal_largest_free_block();
    heap_bytes = big > 12288 ? big - 8192 : 0;
    if (heap_bytes > 256 * 1024) heap_bytes = 256 * 1024;
  }
  for (;;) {
    heap_bytes &= ~7u;
    if (heap_bytes < 4096) FAIL("out of memory (heap)");
    vm.heap = (uint8_t*)malloc(heap_bytes);
    if (vm.heap) {
      vm.startbits = (uint32_t*)calloc(heap_bytes / 256 + 1, 4);
      if (vm.startbits) break;
      free(vm.heap);
      vm.heap = nullptr;
    }
    heap_bytes -= heap_bytes / 8;
  }
  vm.heap_size = heap_bytes;
  memset(vm.heap, 0, 8);
  ObjH* h = H(8);
  h->kind = K_FREE; h->cls = 0; h->mark = 0; h->len = heap_bytes - 8;
  SLOTS(8)[0] = 0;
  sb_set(8);
  vm.free_list = 8;
  vm.heap_free = heap_bytes - 8;
  vm.top = vm.vstack;

  // static constant initializers
  Reader s{sinit, img + len, false};
  for (uint16_t i = 0; i < nsinit; i++) {
    uint16_t slot = s.u16(); uint8_t k = s.u8(); uint32_t v = s.u32();
    if (slot >= vm.nstatic) FAIL("corrupt image (static)");
    if (k == 0) vm.statics[slot] = v;
    else if (k == 1) { vm.statics[slot] = v; vm.statics[slot + 1] = s.u32(); }
    else vm.statics[slot] = str_get((uint16_t)v);
  }
#undef FAIL
  return true;
}

// ======================================================================= run
static int line_of(const Mth& m, uint16_t pc) {
  int line = -1;
  for (uint16_t i = 0; i < m.nlines; i++) {
    if (rd16(m.lines + 4 * i) <= pc) line = rd16(m.lines + 4 * i + 2);
    else break;
  }
  return line;
}

static void print_uncaught() {
  Ref e = vm.exc;
  out_str("\nException in thread \"main\" ");
  out_sid(vm.classes[H(e)->cls].name);
  if (vm.wk[WK_THROWABLE] < vm.nclasses && instance_of(e, vm.wk[WK_THROWABLE])) {
    Ref msg = SLOTS(e)[0];
    if (msg && is_obj(msg) && H(msg)->kind == K_STR) {
      out_str(": ");
      out_buf(str_chars(msg), str_len(msg));
    }
  }
  out_str("\n");
  for (int i = 0; i < vm.ntrace; i++) {
    const Mth& m = vm.methods[vm.trace_mid[i]];
    out_str("    at ");
    out_sid(m.name);
    int line = line_of(m, vm.trace_pc[i]);
    const Cls& k = vm.classes[m.cls];
    out_str("(");
    if (k.src != 0xFFFF) out_sid(k.src); else out_str("?");
    if (line >= 0) { char b[16]; snprintf(b, sizeof b, ":%d", line); out_str(b); }
    out_str(")\n");
  }
}

int vm_run() {
  vm.depth = 0;
  vm.exc = 0;
  vm.ntmp = 0;
  vm.top = vm.vstack;
  uint32_t* args = vm.vstack;
  if (vm.entry_args) args[0] = 0;
  int st = ensure_init(vm.methods[vm.entry].cls, args + vm.entry_args);
  if (st == ST_OK) st = invoke_method(vm.entry, args);
  if (st == ST_EXC) print_uncaught();
  return st;
}
