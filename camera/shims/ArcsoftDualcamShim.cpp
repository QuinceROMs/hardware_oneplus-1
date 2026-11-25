/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// The ArcSoft dual camera libraries filter their buffers with NEON loops that
// load whole vectors, so they read a few bytes past the end of an allocation.
// Scudo places large allocations right before a guard page, which turns that
// read into a SIGSEGV. Pad every allocation these libraries make.

#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

namespace {

constexpr size_t kPadding = 64;

struct Libc {
    void* (*malloc)(size_t);
    void* (*calloc)(size_t, size_t);
    void* (*realloc)(void*, size_t);
    int (*posix_memalign)(void**, size_t, size_t);
    void* (*mpAlloc)(void*, int);
};

template <typename T>
void resolve(void* lib, const char* name, T& out) {
    out = lib ? reinterpret_cast<T>(dlsym(lib, name)) : nullptr;
    if (!out) abort();
}

Libc gReal;
pthread_once_t gOnce = PTHREAD_ONCE_INIT;

void init() {
    void* libc = dlopen("libc.so", RTLD_NOW | RTLD_NOLOAD);
    resolve(libc, "malloc", gReal.malloc);
    resolve(libc, "calloc", gReal.calloc);
    resolve(libc, "realloc", gReal.realloc);
    resolve(libc, "posix_memalign", gReal.posix_memalign);
    resolve(dlopen("libmpbase.so", RTLD_NOW), "MMemAlloc", gReal.mpAlloc);
}

const Libc& real() {
    pthread_once(&gOnce, init);
    return gReal;
}

bool padded(size_t size, size_t* out) {
    return !__builtin_add_overflow(size, kPadding, out);
}

}  // anonymous namespace

extern "C" {

void* malloc(size_t size) {
    size_t total;
    if (!padded(size, &total)) {
        errno = ENOMEM;
        return nullptr;
    }
    return real().malloc(total);
}

void* calloc(size_t count, size_t size) {
    size_t total;
    if (__builtin_mul_overflow(count, size, &total) || !padded(total, &total)) {
        errno = ENOMEM;
        return nullptr;
    }
    return real().calloc(1, total);
}

void* realloc(void* ptr, size_t size) {
    size_t total;
    if (!padded(size, &total)) {
        errno = ENOMEM;
        return nullptr;
    }
    return real().realloc(ptr, total);
}

int posix_memalign(void** out, size_t alignment, size_t size) {
    size_t total;
    if (!padded(size, &total)) return ENOMEM;
    return real().posix_memalign(out, alignment, total);
}

// libmpbase forwards to malloc() when there is no memory manager, through its
// own PLT, which this library cannot interpose.
void* MMemAlloc(void* manager, int size) {
    if (manager != nullptr) return real().mpAlloc(manager, size);
    return size < 0 ? nullptr : malloc(static_cast<size_t>(size));
}

}  // extern "C"
