/*
 * SPDX-FileCopyrightText: 2022-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "AntiFlickerService"

#include <android-base/logging.h>
#include <errno.h>
#include <fcntl.h>
#include <livedisplay/oplus/AntiFlicker.h>
#include <oplus/oplus_display_panel.h>

#include <array>

namespace aidl {
namespace vendor {
namespace lineage {
namespace livedisplay {

namespace {

struct Variant {
    unsigned int get;
    unsigned int set;
};

constexpr std::array<Variant, 3> kVariants = {{
        {PANEL_IOCTL_GET_PWM_PULSE, PANEL_IOCTL_SET_PWM_PULSE},
        {PANEL_IOCTL_GET_PWM_TURBO, PANEL_IOCTL_SET_PWM_TURBO},
        {PANEL_IOCTL_GET_DIMLAYER_BL_EN, PANEL_IOCTL_SET_DIMLAYER_BL_EN},
}};

// The panel driver rejects a command its kernel lacks with EINVAL and a
// feature its panel lacks with EFAULT. Any other error may be transient.
bool isUnsupported(int error) {
    return error == EINVAL || error == EFAULT || error == ENOTTY;
}

}  // namespace

AntiFlicker::AntiFlicker() : mOplusDisplayFd(open("/dev/oplus_display", O_RDWR)) {}

int AntiFlicker::readState(unsigned int* value) {
    if (const int variant = mVariant.load(); variant != kNoVariant) {
        if (ioctl(mOplusDisplayFd, kVariants[variant].get, value) == 0) {
            return variant;
        }
        mVariant.store(kNoVariant);
    }

    // Only remember a variant once every preferred one has been rejected as
    // unsupported, and never one below a variant that has worked before.
    bool canRemember = true;
    for (int variant = 0; variant < static_cast<int>(kVariants.size()); variant++) {
        const unsigned int bit = 1u << variant;
        if (ioctl(mOplusDisplayFd, kVariants[variant].get, value) == 0) {
            mWorkedVariants.fetch_or(bit);
            if (canRemember) {
                mVariant.store(variant);
            }
            return variant;
        }
        canRemember = canRemember && isUnsupported(errno) && !(mWorkedVariants.load() & bit);
    }
    LOG(ERROR) << "Failed to read current AntiFlicker state";
    return kNoVariant;
}

ndk::ScopedAStatus AntiFlicker::getEnabled(bool* _aidl_return) {
    unsigned int value;
    if (readState(&value) == kNoVariant) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
    *_aidl_return = value > 0;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AntiFlicker::setEnabled(bool enabled) {
    unsigned int value;
    const int variant = readState(&value);
    if (variant == kNoVariant) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
    if ((value > 0) == enabled) {
        return ndk::ScopedAStatus::ok();
    }
    value = enabled;
    if (ioctl(mOplusDisplayFd, kVariants[variant].set, &value) != 0) {
        LOG(ERROR) << "Failed to set AntiFlicker state";
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
    return ndk::ScopedAStatus::ok();
}

}  // namespace livedisplay
}  // namespace lineage
}  // namespace vendor
}  // namespace aidl
