/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vendor/oplus/hardware/cameraMDM/2.0/IOPlusCameraMDM.h>

#include <semaphore.h>

#include <mutex>
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
                             uint32_t facing) override;
    Return<bool> setPackageName(const hidl_string& packageName) override;
    Return<void> getPackageName(getPackageName_cb _hidl_cb) override;
    Return<bool> setJankMsg(const hidl_string& msg, uint32_t frameNumber,
                            uint32_t timestamp) override;
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

    std::mutex mLock;
    std::mutex mJankLock;

    int32_t mCameraId = -1;
    uint32_t mLogicalCameraId = 0;
    uint32_t mFacing = 0;
    std::string mPackageName;

    bool mCapturing = false;
    uint32_t mCaptureTs = 0;
    uint32_t mRequiredFps = 0;
    uint32_t mCurrentFps = 0;
    int32_t mJankType = 0;

    int64_t mLastSetNs = 0;
    sem_t mJankSem;
    bool mWatchdogStarted = false;
};

}  // namespace vendor::oplus::hardware::cameraMDM::implementation
