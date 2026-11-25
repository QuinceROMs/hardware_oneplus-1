/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "OPlusCameraMDM.h"

#include <android-base/logging.h>

#include <algorithm>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

namespace vendor::oplus::hardware::cameraMDM::implementation {

namespace {

constexpr char kWatermarkPrefix[] = "watermark";
constexpr std::chrono::milliseconds kHangTimeout{3000};

}  // namespace

OPlusCameraMDM::OPlusCameraMDM() {
    mWatchdog = std::thread(&OPlusCameraMDM::watchdogLoop, this);
}

OPlusCameraMDM::~OPlusCameraMDM() {
    {
        std::lock_guard<std::mutex> lock(mLock);
        mStopWatchdog = true;
    }
    mWatchdogCv.notify_one();
    mWatchdog.join();
}

void OPlusCameraMDM::queueJankLocked(JankType type) {
    if (std::find(mPendingJanks.begin(), mPendingJanks.end(), type) == mPendingJanks.end()) {
        mPendingJanks.push_back(type);
    }
    mJankCv.notify_all();
}

bool OPlusCameraMDM::watchdogArmedLocked() const {
    return mCameraId >= 0 && !mHangReported;
}

void OPlusCameraMDM::noteActivityLocked(bool wasArmed) {
    mLastActivity = std::chrono::steady_clock::now();
    mHangReported = false;
    if (watchdogArmedLocked() != wasArmed) {
        mWatchdogCv.notify_one();
    }
}

void OPlusCameraMDM::watchdogLoop() {
    std::unique_lock<std::mutex> lock(mLock);
    while (!mStopWatchdog) {
        if (!watchdogArmedLocked()) {
            mWatchdogCv.wait(lock);
            continue;
        }
        // Sample once per timeout period like the OxygenOS service rather than
        // waiting for an exact deadline: below 10 fps the HAL's heartbeat is
        // itself more than 3 s apart.
        if (mWatchdogCv.wait_for(lock, kHangTimeout) == std::cv_status::no_timeout) {
            continue;
        }
        if (watchdogArmedLocked() &&
            std::chrono::steady_clock::now() - mLastActivity >
                    kHangTimeout + std::chrono::milliseconds(mCaptureMs)) {
            mHangReported = true;
            queueJankLocked(JankType::Hang);
        }
    }
}

Return<bool> OPlusCameraMDM::setCameraId(uint32_t cameraId, uint32_t logicalCameraId,
                                         uint32_t fps) {
    std::lock_guard<std::mutex> lock(mLock);
    const bool wasArmed = watchdogArmedLocked();
    mCameraId = cameraId;
    mLogicalCameraId = logicalCameraId;
    mFps = fps;
    noteActivityLocked(wasArmed);
    return true;
}

Return<bool> OPlusCameraMDM::setPackageName(const hidl_string& packageName) {
    std::lock_guard<std::mutex> lock(mLock);
    mPackageName = packageName.c_str();
    return true;
}

Return<void> OPlusCameraMDM::getPackageName(getPackageName_cb _hidl_cb) {
    hidl_string name;
    {
        std::lock_guard<std::mutex> lock(mLock);
        name = mPackageName;
    }
    _hidl_cb(name);
    return Void();
}

Return<bool> OPlusCameraMDM::setJankMsg(const hidl_string& msg, uint32_t value1,
                                        uint32_t value2) {
    std::lock_guard<std::mutex> lock(mLock);
    const bool wasArmed = watchdogArmedLocked();
    const char* str = msg.c_str();
    if (strncmp(str, "CaptureStarted", 14) == 0) {
        mCapturing = true;
        mCaptureMs = value1;
    } else if (strncmp(str, "CaptureStopped", 14) == 0) {
        mCapturing = false;
        mCaptureMs = 0;
    } else if (strncmp(str, "SlowFPS", 8) == 0) {
        LOG(ERROR) << "setJankMsg: OP_EXT msg " << str;
        mRequiredFps = value1;
        mFps = value2;
        queueJankLocked(JankType::SlowFps);
    }
    noteActivityLocked(wasArmed);
    return true;
}

Return<void> OPlusCameraMDM::getJankMsg(getJankMsg_cb _hidl_cb) {
    char buf[1024] = "{}";
    {
        std::unique_lock<std::mutex> lock(mLock);
        const uint64_t waiter = ++mJankWaiter;
        mJankCv.notify_all();
        mJankCv.wait(lock, [&] { return mJankWaiter != waiter || !mPendingJanks.empty(); });

        if (mJankWaiter == waiter) {
            const JankType type = mPendingJanks.front();
            mPendingJanks.pop_front();
            if (mCameraId < 0) {
                LOG(INFO) << "getJankMsg: OP_EXT camera is closed, donot send jank message";
            } else if (type == JankType::Hang) {
                snprintf(buf, sizeof(buf),
                         "{\"errorType\": \"Camera Hang\",\"data\": [{\"paramKey\": "
                         "\"cameraId\",\"value\": \"%d\"},{\"paramKey\": "
                         "\"activeCameraId\",\"value\": \"%d\"},{\"paramKey\": "
                         "\"fps\",\"value\": \"%d\"},{\"paramKey\": "
                         "\"isHangWhileCapture\",\"value\": \"%s\"}]}",
                         mCameraId, mLogicalCameraId, mFps, mCapturing ? "true" : "false");
            } else {
                snprintf(buf, sizeof(buf),
                         "{\"errorType\": \"Slow FPS\",\"data\": [{\"paramKey\": "
                         "\"cameraId\",\"value\": \"%d\"},{\"paramKey\": "
                         "\"activeCameraId\",\"value\": \"%d\"},{\"paramKey\": "
                         "\"requiredfps\",\"value\": \"%d\"},{\"paramKey\": "
                         "\"currentfps\",\"value\": \"%d\"}]}",
                         mCameraId, mLogicalCameraId, mRequiredFps, mFps);
            }
        }
    }

    LOG(INFO) << "getJankMsg: OP_EXT msg: " << buf;
    _hidl_cb(buf);
    return Void();
}

void OPlusCameraMDM::closeStaleFdsLocked(const struct stat* reopened) {
    for (auto it = mOpenFds.begin(); it != mOpenFds.end();) {
        struct stat st;
        if (fstat(*it, &st) == 0 &&
            (st.st_nlink == 0 || (reopened != nullptr && st.st_dev == reopened->st_dev &&
                                  st.st_ino == reopened->st_ino))) {
            LOG(DEBUG) << "Closing stale fd " << *it;
            close(*it);
            it = mOpenFds.erase(it);
        } else {
            ++it;
        }
    }
}

Return<bool> OPlusCameraMDM::file_access(const hidl_string& path) {
    if (access(path.c_str(), F_OK) != 0) {
        LOG(ERROR) << path.c_str() << " is not exist!";
        return false;
    }
    return true;
}

Return<bool> OPlusCameraMDM::file_delete(const hidl_string& path) {
    DIR* dir = opendir(path.c_str());
    if (dir == nullptr) {
        LOG(ERROR) << "file_delete: check file fail " << path.c_str();
        return false;
    }

    bool deleted = false;
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (strncmp(entry->d_name, kWatermarkPrefix, strlen(kWatermarkPrefix)) == 0) {
            std::string full = path.c_str();
            if (full.back() != '/') full += '/';
            full += entry->d_name;
            if (remove(full.c_str()) == 0) {
                LOG(DEBUG) << "file_delete: file " << full << " deleted";
                deleted = true;
            } else {
                LOG(ERROR) << "file_delete: no file to delete " << full;
            }
        }
    }
    closedir(dir);

    std::lock_guard<std::mutex> lock(mFileLock);
    closeStaleFdsLocked(nullptr);
    return deleted;
}

Return<int32_t> OPlusCameraMDM::file_open(const hidl_string& path) {
    std::lock_guard<std::mutex> lock(mFileLock);
    int fd = open(path.c_str(), O_RDWR | O_CREAT, 0644);
    if (fd < 0) {
        LOG(ERROR) << "file_open: can't open " << path.c_str() << ", fd = " << fd;
        return fd;
    }
    LOG(DEBUG) << "file_open: open file " << fd;

    struct stat st;
    closeStaleFdsLocked(fstat(fd, &st) == 0 ? &st : nullptr);
    mOpenFds.insert(fd);
    return fd;
}

Return<bool> OPlusCameraMDM::file_write(int32_t fd, const hidl_vec<uint8_t>& data,
                                        uint32_t size) {
    std::lock_guard<std::mutex> lock(mFileLock);
    if (mOpenFds.count(fd) == 0) {
        LOG(ERROR) << "file_write: invalid fd " << fd;
        return false;
    }
    if (size > data.size()) {
        LOG(ERROR) << "file_write: size exceeds input buffer";
        return false;
    }
    size_t written = 0;
    while (written < size) {
        const ssize_t n = TEMP_FAILURE_RETRY(write(fd, data.data() + written, size - written));
        if (n <= 0) {
            LOG(ERROR) << "file_write: failed after " << written << " bytes";
            return false;
        }
        written += n;
    }
    return true;
}

Return<void> OPlusCameraMDM::file_read(int32_t fd, uint32_t size, file_read_cb _hidl_cb) {
    if (size == 0) {
        LOG(ERROR) << "file_read: invalid size 0";
        _hidl_cb(false, {}, 0);
        return Void();
    }
    std::lock_guard<std::mutex> lock(mFileLock);
    if (mOpenFds.count(fd) == 0) {
        LOG(ERROR) << "file_read: invalid fd " << fd;
        _hidl_cb(false, {}, 0);
        return Void();
    }

    hidl_vec<uint8_t> out;
    out.resize(size);
    ssize_t n = read(fd, out.data(), size);
    if (n < 0) n = 0;
    LOG(DEBUG) << "file_read: read data size " << n;
    out.resize(n);
    _hidl_cb(n > 0, out, n);
    return Void();
}

Return<bool> OPlusCameraMDM::file_close(int32_t fd) {
    std::lock_guard<std::mutex> lock(mFileLock);
    if (mOpenFds.erase(fd) == 0) {
        LOG(ERROR) << "file_close: invalid fd " << fd;
        return false;
    }
    const int result = close(fd);
    LOG(DEBUG) << "file_close: close fd " << fd << ", result = " << result;
    return result == 0;
}

}  // namespace vendor::oplus::hardware::cameraMDM::implementation
