// KTVM - a tiny JVM-bytecode interpreter for running Kotlin on microcontrollers.
#pragma once
#include <stddef.h>
#include <stdint.h>

enum { ST_OK = 0, ST_EXC = 1, ST_ABORT = 2, ST_FATAL = 3 };

// Load an image (the buffer must stay alive until vm_unload). heap_bytes = size of the GC heap.
bool vm_load(const uint8_t* img, size_t len, uint32_t heap_bytes, char* err, size_t errlen);
int vm_run();  // run main(); prints uncaught exceptions; returns ST_*
void vm_unload();
uint32_t vm_heap_free();

extern volatile bool vm_abort_flag;  // set from another task to stop the running program
