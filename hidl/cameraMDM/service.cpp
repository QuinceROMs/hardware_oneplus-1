/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android-base/logging.h>
#include <hidl/HidlTransportSupport.h>

#include "OPlusCameraMDM.h"

using vendor::oplus::hardware::cameraMDM::implementation::OPlusCameraMDM;

int main() {
    LOG(INFO) << "OPCameraHIDL Start";
    android::hardware::configureRpcThreadpool(3, true);
    OPlusCameraMDM* service = new OPlusCameraMDM();
    if (service->registerAsService() == android::OK) {
        LOG(INFO) << "OPCameraHIDL Ready";
        android::hardware::joinRpcThreadpool();
    } else {
        LOG(ERROR) << "OPCameraHIDL Fail to register native service";
        delete service;
        return 1;
    }
    return 0;
}
