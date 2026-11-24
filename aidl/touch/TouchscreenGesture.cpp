/*
 * SPDX-FileCopyrightText: 2025-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.touch-service.oplus"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>

#include <OplusTouchConstants.h>
#include <TouchscreenGestureConfig.h>

#include <limits>

using ::android::base::ParseUint;
using ::android::base::ReadFileToString;
using ::android::base::StartsWithIgnoreCase;
using ::android::base::Trim;
using ::android::base::WriteStringToFile;

namespace {

// The touch drivers print the mask with "%x", so without a 0x prefix, and
// print nothing while no touch IC is bound.
bool parseGestureMask(const std::string& contents, int* mask) {
    std::string hex = Trim(contents);
    if (!StartsWithIgnoreCase(hex, "0x")) {
        hex = "0x" + hex;
    }
    unsigned int value;
    if (!ParseUint(hex, &value, static_cast<unsigned int>(std::numeric_limits<int>::max()))) {
        return false;
    }
    *mask = static_cast<int>(value);
    return true;
}

}  // anonymous namespace

namespace aidl {
namespace vendor {
namespace lineage {
namespace touch {

TouchscreenGesture::TouchscreenGesture(std::shared_ptr<IOplusTouch> oplusTouch)
    : mOplusTouch(std::move(oplusTouch)) {}

ndk::ScopedAStatus TouchscreenGesture::getSupportedGestures(std::vector<Gesture>* _aidl_return) {
    std::vector<Gesture> gestures;

    for (const auto& [id, name] : kGestureNames) {
        if (kSupportedGestures & (1 << id)) {
            gestures.push_back({static_cast<int>(gestures.size()), name, kGestureStartKey + id});
        }
    }

    *_aidl_return = gestures;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus TouchscreenGesture::setGestureEnabled(const Gesture& gesture, bool enabled) {
    const auto updateMask = [&](int contents) {
        const int bit = 1 << (gesture.keycode - kGestureStartKey);
        return enabled ? contents | bit : contents & ~bit;
    };

    if (mOplusTouch) {
        std::string tmp;
        int mask;
        mOplusTouch->touchReadNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID,
                                       OplusTouchConstants::DOUBLE_TAP_INDEP_NODE, &tmp);
        if (!parseGestureMask(tmp, &mask)) {
            LOG(ERROR) << "Failed to read current AIDL gesture mask";
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        }
        const int contents = updateMask(mask);
        int aidl_return = 0;
        mOplusTouch->touchWriteNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID,
                                        OplusTouchConstants::DOUBLE_TAP_ENABLE_NODE, "1",
                                        &aidl_return);
        mOplusTouch->touchWriteNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID,
                                        OplusTouchConstants::DOUBLE_TAP_INDEP_NODE,
                                        std::to_string(contents), &aidl_return);
    } else {
        for (const auto& node : ::android::base::Split(GESTURE_ENABLE_INDEP_NODES, " ")) {
            if (node.empty()) continue;

            std::string tmp;
            int mask;
            if (!ReadFileToString(node, &tmp) || !parseGestureMask(tmp, &mask)) {
                LOG(ERROR) << "Failed to read current gesture mask from " << node;
                return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
            }
            const int contents = updateMask(mask);
            if (!WriteStringToFile(std::to_string(contents), node, true)) {
                return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
            }
        }
    }

    return ndk::ScopedAStatus::ok();
}

}  // namespace touch
}  // namespace lineage
}  // namespace vendor
}  // namespace aidl
