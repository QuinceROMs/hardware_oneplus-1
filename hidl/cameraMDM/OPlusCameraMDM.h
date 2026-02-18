/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vendor/oplus/hardware/cameraMDM/2.0/IOPlusCameraMDM.h>

#include <semaphore.h>
#include <sys/stat.h>

#include <mutex>
#include <set>
#include <string>

namespace vendor::oplus::hardware::cameraMDM::implementation {

using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;

class OPlusCameraMDM final : public V2_0::IOPlusCameraMDM {
  public:
    OPlusCameraMDM();

    Return<bool> setCameraId(uint32_t cameraId, uint32_t logicalCameraId,
                             uint32_t fps) override;
    Return<bool> setPackageName(const hidl_string& packageName) override;
    Return<void> getPackageName(getPackageName_cb _hidl_cb) override;
    Return<bool> setJankMsg(const hidl_string& msg, uint32_t value1,
                            uint32_t value2) override;
    Return<void> getJankMsg(getJankMsg_cb _hidl_cb) override;
    Return<bool> file_access(const hidl_string& path) override;
    Return<bool> file_delete(const hidl_string& path) override;
    Return<int32_t> file_open(const hidl_string& path) override;
    Return<bool> file_write(int32_t fd, const hidl_vec<uint8_t>& data,
                            uint32_t size) override;
    Return<void> file_read(int32_t fd, uint32_t size, file_read_cb _hidl_cb) override;
    Return<bool> file_close(int32_t fd) override;

  private:
    static void* watchdogThread(void* arg);

    void postJankSem();
    void closeStaleFdsLocked(const struct stat* reopened);

    std::mutex mLock;
    std::mutex mJankLock;
    std::mutex mFileLock;

    // Descriptors handed out by file_open. The camera app closes them only after
    // a failed write, so each one is closed here once its file is unlinked or
    // opened again; otherwise every deleted watermark file stays allocated.
    std::set<int32_t> mOpenFds;

    int32_t mCameraId = -1;
    int32_t mLogicalCameraId = -1;
    std::string mPackageName;

    bool mCapturing = false;
    int32_t mCaptureMs = 0;
    int32_t mRequiredFps = -1;
    // Shared by setCameraId and SlowFPS, as the jank reports print it for both.
    int32_t mFps = -1;
    int32_t mJankType = -1;

    int64_t mLastSetNs = 0;
    sem_t mJankSem;
    bool mWatchdogStarted = false;
};

}  // namespace vendor::oplus::hardware::cameraMDM::implementation
