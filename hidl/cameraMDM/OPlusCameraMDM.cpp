/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "OPlusCameraMDM.h"

#include <android-base/logging.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

namespace vendor::oplus::hardware::cameraMDM::implementation {

namespace {

constexpr char kWatermarkDir[] = "/data/vendor/camera/watermark";
constexpr char kWatermarkPrefix[] = "watermark";
constexpr int64_t kHangTimeoutMs = 3000;

int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

}  // namespace

OPlusCameraMDM::OPlusCameraMDM() {
    sem_init(&mJankSem, 0, 0);
}

void OPlusCameraMDM::postJankSem() {
    sem_post(&mJankSem);
}

void* OPlusCameraMDM::watchdogThread(void* arg) {
    auto* self = static_cast<OPlusCameraMDM*>(arg);
    while (true) {
        sleep(3);
        std::lock_guard<std::mutex> lock(self->mLock);
        if (self->mCameraId >= 0 &&
            self->mCaptureTs + kHangTimeoutMs < (nowNs() - self->mLastSetNs) / 1000000) {
            self->mJankType = 0;
            self->postJankSem();
        }
    }
    return nullptr;
}

Return<bool> OPlusCameraMDM::setCameraId(uint32_t cameraId, uint32_t logicalCameraId,
                                         uint32_t facing) {
    std::lock_guard<std::mutex> lock(mLock);
    mLastSetNs = nowNs();
    mCameraId = cameraId;
    mLogicalCameraId = logicalCameraId;
    mFacing = facing;
    if (!mWatchdogStarted) {
        mWatchdogStarted = true;
        pthread_t thread;
        pthread_create(&thread, nullptr, watchdogThread, this);
        pthread_detach(thread);
    }
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

Return<bool> OPlusCameraMDM::setJankMsg(const hidl_string& msg, uint32_t frameNumber,
                                        uint32_t timestamp) {
    std::lock_guard<std::mutex> lock(mLock);
    const char* str = msg.c_str();
    if (strncmp(str, "CaptureStarted", 14) == 0) {
        mCapturing = true;
        mCaptureTs = timestamp;
    } else if (strncmp(str, "CaptureStopped", 14) == 0) {
        mCapturing = false;
        mCaptureTs = 0;
    } else if (strncmp(str, "SlowFPS", 8) == 0) {
        LOG(ERROR) << "setJankMsg: OP_EXT msg " << str;
        mRequiredFps = frameNumber;
        mCurrentFps = timestamp;
        mJankType = 1;
        postJankSem();
    }
    return true;
}

Return<void> OPlusCameraMDM::getJankMsg(getJankMsg_cb _hidl_cb) {
    if (mJankLock.try_lock()) {
        mJankLock.unlock();
    } else {
        // Another getJankMsg is already parked in sem_wait; give this one
        // the "nothing to report" answer instead of queueing behind it.
        {
            std::lock_guard<std::mutex> lock(mLock);
            mJankType = -1;
        }
        postJankSem();
    }

    mJankLock.lock();
    while (sem_wait(&mJankSem) != 0 && errno == EINTR) {
    }

    char buf[1024] = {};
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mCameraId < 0) {
            LOG(INFO) << "getJankMsg: OP_EXT camera is closed, donot send jank message";
            mJankType = -1;
        } else if (mJankType == 0) {
            snprintf(buf, sizeof(buf),
                     "{\"errorType\": \"Camera Hang\",\"data\": [{\"paramKey\": "
                     "\"cameraId\",\"value\": \"%d\"},{\"paramKey\": "
                     "\"activeCameraId\",\"value\": \"%d\"},{\"paramKey\": "
                     "\"fps\",\"value\": \"%d\"},{\"paramKey\": "
                     "\"isHangWhileCapture\",\"value\": \"%s\"}]}",
                     mCameraId, mLogicalCameraId, mCurrentFps, mCapturing ? "true" : "false");
        } else if (mJankType == 1) {
            snprintf(buf, sizeof(buf),
                     "{\"errorType\": \"Slow FPS\",\"data\": [{\"paramKey\": "
                     "\"cameraId\",\"value\": \"%d\"},{\"paramKey\": "
                     "\"activeCameraId\",\"value\": \"%d\"},{\"paramKey\": "
                     "\"requiredfps\",\"value\": \"%d\"},{\"paramKey\": "
                     "\"currentfps\",\"value\": \"%d\"}]}",
                     mCameraId, mLogicalCameraId, mRequiredFps, mCurrentFps);
        }
    }

    LOG(INFO) << "getJankMsg: OP_EXT msg: " << buf;
    _hidl_cb(buf);
    mJankLock.unlock();
    return Void();
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
    return deleted;
}

Return<int32_t> OPlusCameraMDM::file_open(const hidl_string& path) {
    if (access(kWatermarkDir, F_OK) != 0) {
        if (mkdir(kWatermarkDir, 0777) != 0 && errno != EEXIST) {
            LOG(ERROR) << "Failed to create " << kWatermarkDir << " directory";
            return -1;
        }
        chmod(kWatermarkDir, 0777);
    }

    int fd = open(path.c_str(), O_RDWR | O_CREAT, 0644);
    if (fd < 0) {
        LOG(ERROR) << "file_open: can't open " << path.c_str() << ", fd = " << fd;
    } else {
        LOG(DEBUG) << "file_open: open file " << fd;
    }
    return fd;
}

Return<bool> OPlusCameraMDM::file_write(int32_t fd, const hidl_vec<uint8_t>& data,
                                        uint32_t size) {
    if (fd < 0) {
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
    if (fd < 0) {
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
    if (fd < 0) {
        LOG(ERROR) << "file_close: invalid fd " << fd;
        return false;
    }
    const int result = close(fd);
    LOG(DEBUG) << "file_close: close fd " << fd << ", result = " << result;
    return result == 0;
}

}  // namespace vendor::oplus::hardware::cameraMDM::implementation
