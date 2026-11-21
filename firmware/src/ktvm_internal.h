// Internal VM structures shared between the interpreter and the natives.
#pragma once
#include <stdint.h>
#include <string.h>

#include "ktvm.h"

typedef uint32_t Ref;  // 0 = null, otherwise byte offset into the heap

enum Kind : uint8_t {
  K_FREE = 0, K_OBJ, K_STR,
  K_ARR_Z, K_ARR_B, K_ARR_C, K_ARR_S, K_ARR_I, K_ARR_F, K_ARR_J, K_ARR_D, K_ARR_REF
};

struct ObjH {
  uint16_t cls;
  uint8_t kind;
  uint8_t mark;
  uint32_t len;  // K_OBJ: slots, K_STR: bytes, arrays: elements, K_FREE: block bytes
};

enum { CP_NONE, CP_INT, CP_WIDE, CP_STR, CP_CLASS, CP_FIELD_S, CP_FIELD_I, CP_METHOD, CP_METHOD_V, CP_UNRESOLVED };
enum { CID_OBJECT = 0, CID_STRING = 1, CID_UNKNOWN = 0xFFFD, CID_ARRAY = 0xFFFE, CID_ANY = 0xFFFF };
enum { NATIVE_BIT = 0x8000, NO_METHOD = 0xFFFF };
enum { CF_INTERFACE = 1, CF_ABSTRACT = 2, CF_BUILTIN = 4 };
enum { MF_STATIC = 1 };

// well-known exception classes (order = linker.WELL_KNOWN)
enum {
  WK_THROWABLE, WK_NPE, WK_ARITH, WK_AIOOBE, WK_CCE, WK_NEGSIZE, WK_OOM, WK_SOE,
  WK_SIOOBE, WK_UNSUPPORTED, WK_NUMFMT, WK_ILLSTATE, WK_COUNT_MAX = 32
};

struct CpE {
  uint8_t tag, aux;
  uint16_t a;
  uint32_t b;
};

struct Cls {
  uint16_t name, src, super_, ninst, clinit;
  uint8_t flags, init;
  uint16_t nsupers, nvt, ncp;
  const uint8_t* supers;  // u16[nsupers] (unaligned)
  const uint8_t* vt;      // (u16 sel, u16 target)[nvt] sorted (unaligned)
  CpE* cp;
};

struct Mth {
  uint16_t cls, name;
  uint8_t flags, retslots;
  uint16_t max_stack, max_locals, argslots;
  uint32_t code_len;
  const uint8_t* code;
  uint16_t nexc;
  const uint8_t* exc;  // (u16 start,end,handler,cls)[nexc]
  uint16_t nlines;
  const uint8_t* lines;  // (u16 pc, u16 line)[nlines]
};

struct Vm {
  const uint8_t* img;
  uint16_t nstrings, nclasses, nmethods, nstatic, entry;
  uint8_t entry_args;
  uint16_t wk[WK_COUNT_MAX];
  const uint8_t** str_ptr;
  uint16_t* str_len;
  Ref* str_ref;  // interned string objects
  Cls* classes;
  Mth* methods;
  uint32_t* statics;
  // heap
  uint8_t* heap;
  uint32_t heap_size;
  uint32_t* startbits;
  Ref free_list;
  uint32_t heap_free;
  // stack
  uint32_t* vstack;
  uint32_t vstack_slots;
  uint32_t* top;  // end of the live part of vstack
  int depth;
  Ref exc;  // pending exception
  Ref tmp[16];
  int ntmp;
  // stack trace of the pending exception
  uint16_t trace_mid[8], trace_pc[8];
  int ntrace;
};

extern Vm vm;

static inline uint16_t rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static inline uint32_t rd32(const uint8_t* p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static inline ObjH* H(Ref r) { return (ObjH*)(vm.heap + r); }
static inline uint8_t* DATA(Ref r) { return vm.heap + r + sizeof(ObjH); }
static inline uint32_t* SLOTS(Ref r) { return (uint32_t*)(vm.heap + r + sizeof(ObjH)); }
static inline float i2f_bits(uint32_t u) { float f; memcpy(&f, &u, 4); return f; }
static inline uint32_t f2i_bits(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }
static inline int64_t getL(const uint32_t* p) { return (int64_t)((uint64_t)p[0] | ((uint64_t)p[1] << 32)); }
static inline void putL(uint32_t* p, int64_t v) { p[0] = (uint32_t)v; p[1] = (uint32_t)((uint64_t)v >> 32); }
static inline double getD(const uint32_t* p) { int64_t l = getL(p); double d; memcpy(&d, &l, 8); return d; }
static inline void putD(uint32_t* p, double d) { int64_t l; memcpy(&l, &d, 8); putL(p, l); }

// heap / objects
Ref gc_alloc(uint8_t kind, uint16_t cls, uint32_t len, uint32_t payload);
void gc_collect();
Ref new_string(const char* s, uint32_t n);
Ref new_array(uint8_t kind, uint32_t n);
uint32_t elem_size(uint8_t kind);
bool is_array_kind(uint8_t k);
const char* str_chars(Ref s);  // NUL-terminated
uint32_t str_len(Ref s);
static inline void push_tmp(Ref r) { vm.tmp[vm.ntmp++] = r; }
static inline void pop_tmp(int n = 1) { vm.ntmp -= n; }

// classes
bool instance_of(Ref r, uint16_t cid);
uint16_t vt_lookup(uint16_t cid, uint16_t sel);

// exceptions: create & set vm.exc, return ST_EXC
int throw_wk(int wk, const char* msg);
int vm_fatal(const char* fmt, ...);

// printing
void out_str(const char* s);
void out_buf(const char* s, size_t n);
void fmt_double(double v, bool is_float, char* out);

int native_call(uint16_t nid, uint32_t* args);
int invoke_method(uint16_t mid, uint32_t* args);
int invoke_target(uint16_t target, uint32_t* args);
