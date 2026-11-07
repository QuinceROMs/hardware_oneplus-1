/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * SNPE in the stock face unlock libraries loads this library through the libOpenCL.so link next to
 * it. SNPE only runs on GPU models from its own table, which it parses from CL_DEVICE_VERSION as
 * "OpenCL <major>.<minor> Adreno(TM) <model>". Drivers differ in that string, some even name
 * another model, e.g. Adreno 650 on an Adreno 640. Present the model the kernel identifies in that
 * form. Everything else resolves to the vendor libOpenCL.so this library depends on.
 */

#define LOG_TAG "android.hardware.biometrics.face-service.oneplus"
#define CL_TARGET_OPENCL_VERSION 300

#include <CL/cl.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <log/log.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define GPU_MODEL_PATH "/sys/class/kgsl/kgsl-3d0/gpu_model"

typedef cl_int (*GetDeviceInfo)(cl_device_id, cl_device_info, size_t, void*, size_t*);

static GetDeviceInfo sGetDeviceInfo;
static unsigned sGpuModel;

/* gpu_model reads like "Adreno640v2". */
static unsigned readGpuModel(void) {
    char buf[32] = {0};
    int fd = open(GPU_MODEL_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t len = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    unsigned model = 0;
    if (len <= 0 || sscanf(buf, "Adreno%u", &model) != 1) return 0;
    return model;
}

__attribute__((constructor)) static void init(void) {
    /* This library has its own soname, so the name only matches the vendor library. */
    void* lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_NOLOAD);
    sGetDeviceInfo = lib ? (GetDeviceInfo)dlsym(lib, "clGetDeviceInfo") : NULL;
    if (!sGetDeviceInfo) ALOGE("Vendor clGetDeviceInfo unavailable: %s", dlerror());
    sGpuModel = readGpuModel();
}

/* Returns the length of the replacement CL_DEVICE_VERSION including NUL, or 0 to keep the driver's. */
static size_t deviceVersion(cl_device_id device, char* out, size_t size) {
    char version[256];
    size_t len = 0;
    if (sGpuModel == 0 ||
        sGetDeviceInfo(device, CL_DEVICE_VERSION, sizeof(version), version, &len) != CL_SUCCESS) {
        return 0;
    }
    unsigned major, minor;
    if (sscanf(version, "OpenCL %u.%u", &major, &minor) != 2) return 0;
    int n = snprintf(out, size, "OpenCL %u.%u Adreno(TM) %u", major, minor, sGpuModel);
    if (n < 0 || (size_t)n >= size || !strcmp(out, version)) return 0;
    static bool logged;
    if (!logged) {
        logged = true;
        ALOGI("Reporting \"%s\" instead of \"%s\"", out, version);
    }
    return (size_t)n + 1;
}

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(cl_device_id device, cl_device_info param_name,
                                                size_t param_value_size, void* param_value,
                                                size_t* param_value_size_ret) {
    if (!sGetDeviceInfo) return CL_INVALID_DEVICE;
    if (param_name == CL_DEVICE_VERSION) {
        char version[64];
        size_t len = deviceVersion(device, version, sizeof(version));
        if (len) {
            if (param_value && param_value_size < len) return CL_INVALID_VALUE;
            if (param_value) memcpy(param_value, version, len);
            if (param_value_size_ret) *param_value_size_ret = len;
            return CL_SUCCESS;
        }
    }
    return sGetDeviceInfo(device, param_name, param_value_size, param_value, param_value_size_ret);
}
