/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/oplus/hardware/touch/IOplusTouch.h>

#include <OplusTouchConstants.h>

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

}  // namespace OplusTouchNode
