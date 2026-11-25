/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vendor/oplus/hardware/cameraMDM/2.0/IOPlusCameraMDM.h>

#include <sys/stat.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <set>
#include <string>
#include <thread>

namespace vendor::oplus::hardware::cameraMDM::implementation {

using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;

class OPlusCameraMDM final : public V2_0::IOPlusCameraMDM {
  public:
    OPlusCameraMDM();
    ~OPlusCameraMDM() override;

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
    enum class JankType { Hang, SlowFps };

    void watchdogLoop();
    bool watchdogArmedLocked() const;
    void noteActivityLocked(bool wasArmed);

    void queueJankLocked(JankType type);
    void closeStaleFdsLocked(const struct stat* reopened);

    std::mutex mLock;
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

    // A report reads the current state when it is sent, so each type is queued
    // at most once.
    std::deque<JankType> mPendingJanks;
    // Bumped by every getJankMsg call; a call still waiting from before then
    // returns an empty report and leaves the queue to the newer one.
    uint64_t mJankWaiter = 0;
    std::condition_variable mJankCv;

    // Refreshed by every call from the camera HAL. The sm8150 HAL calls
    // setCameraId every 30 frames while streaming, so it ages only once the
    // pipeline stops delivering frames.
    std::chrono::steady_clock::time_point mLastActivity;
    bool mHangReported = false;
    bool mStopWatchdog = false;
    std::condition_variable mWatchdogCv;
    std::thread mWatchdog;
};

}  // namespace vendor::oplus::hardware::cameraMDM::implementation
