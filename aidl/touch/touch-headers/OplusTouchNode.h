/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/oplus/hardware/touch/IOplusTouch.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>

#include <OplusTouchConstants.h>

#include <limits>
#include <string>

namespace OplusTouchNode {

using ::aidl::vendor::oplus::hardware::touch::IOplusTouch;

inline bool Read(IOplusTouch& touch, int node, std::string* value) {
    return touch.touchReadNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID, node, value).isOk();
}

inline bool Write(IOplusTouch& touch, int node, const std::string& value) {
    int ret = 0;
    return touch.touchWriteNodeFile(OplusTouchConstants::DEFAULT_TP_IC_ID, node, value, &ret)
                   .isOk() &&
           ret >= 0;
}

// The touch drivers print the mask with "%x", so without a 0x prefix, and
// print nothing while no touch IC is bound.
inline bool ParseMask(const std::string& contents, int* mask) {
    std::string hex = ::android::base::Trim(contents);
    if (!::android::base::StartsWithIgnoreCase(hex, "0x")) {
        hex = "0x" + hex;
    }
    unsigned int value;
    if (!::android::base::ParseUint(hex, &value,
                                    static_cast<unsigned int>(std::numeric_limits<int>::max()))) {
        return false;
    }
    *mask = static_cast<int>(value);
    return true;
}

inline int SetMaskBit(int mask, int bit, bool enabled) {
    return enabled ? mask | bit : mask & ~bit;
}

inline bool UpdateGestureMask(IOplusTouch& touch, int bit, bool enabled) {
    std::string contents;
    int mask;
    if (!Read(touch, OplusTouchConstants::DOUBLE_TAP_INDEP_NODE, &contents) ||
        !ParseMask(contents, &mask)) {
        return false;
    }
    return Write(touch, OplusTouchConstants::DOUBLE_TAP_ENABLE_NODE, "1") &&
           Write(touch, OplusTouchConstants::DOUBLE_TAP_INDEP_NODE,
                 std::to_string(SetMaskBit(mask, bit, enabled)));
}

}  // namespace OplusTouchNode
