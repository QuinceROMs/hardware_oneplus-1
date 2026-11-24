/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <aidl/android/hardware/power/BnPower.h>
#include <aidl/vendor/oplus/hardware/charger/ICharger.h>
#include <aidl/vendor/oplus/hardware/touch/IOplusTouch.h>

#include <android-base/logging.h>
#include <android/binder_manager.h>

#include <OplusTouchConstants.h>
#include <OplusTouchNode.h>

#include "charger-cool-down.h"

using aidl::android::hardware::power::Mode;
using aidl::vendor::oplus::hardware::charger::ICharger;
using aidl::vendor::oplus::hardware::touch::IOplusTouch;

#ifdef LIBPERFMGR_EXT
namespace aidl::google::hardware::power::impl::pixel {
#else
namespace aidl::android::hardware::power::impl {
#endif

bool isDeviceSpecificModeSupported(Mode type, bool* _aidl_return) {
    switch (type) {
#ifndef TAP_TO_WAKE_NODE
        case Mode::DOUBLE_TAP_TO_WAKE:
            *_aidl_return = true;
            return true;
#endif
        default:
            return false;
    }
}

bool setDeviceSpecificMode(Mode type, bool enabled) {
    switch (type) {
#ifndef TAP_TO_WAKE_NODE
        case Mode::DOUBLE_TAP_TO_WAKE: {
            const std::string instance = std::string() + IOplusTouch::descriptor + "/default";
            std::shared_ptr<IOplusTouch> oplusTouch = IOplusTouch::fromBinder(
                    ndk::SpAIBinder(AServiceManager_waitForService(instance.c_str())));
            LOG(INFO) << "Power mode: " << toString(type) << " isDoubleTapEnabled: " << enabled;

            if (!oplusTouch ||
                !OplusTouchNode::UpdateGestureMask(
                        *oplusTouch, OplusTouchConstants::DOUBLE_TAP_GESTURE, enabled)) {
                LOG(ERROR) << "Failed to update the double tap gesture mask";
            }
            return true;
        }
#endif
        case Mode::INTERACTIVE: {
            const std::string instance = std::string() + ICharger::descriptor + "/default";
            std::shared_ptr<ICharger> charger = ICharger::fromBinder(
                    ndk::SpAIBinder(AServiceManager_checkService(instance.c_str())));
            if (charger) {
                int aidl_return = 0;
                charger->notifyScreenStatus(enabled, &aidl_return);
            }
            oplus::power::setChargerCoolDownActive(enabled);
            return false;
        }
        default:
            return false;
    }
}

}  // namespace
