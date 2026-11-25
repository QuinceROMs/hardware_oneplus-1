/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.doze

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings

class DozeApplication : Application() {
    private val dozeSettingsObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Utils.checkDozeService(this@DozeApplication)
            }
        }

    override fun onCreate() {
        super.onCreate()
        for (setting in DOZE_SETTINGS) {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(setting),
                false,
                dozeSettingsObserver,
                UserHandle.USER_ALL,
            )
        }
    }

    companion object {
        // The settings read by AmbientDisplayConfiguration.pulseOnNotificationEnabled()
        // and alwaysOnEnabled(), which decide in Utils.checkDozeService() whether
        // DozeService runs.
        private val DOZE_SETTINGS =
            arrayOf(
                Settings.Secure.DOZE_ENABLED,
                Settings.Secure.DOZE_ALWAYS_ON,
                Settings.Secure.DOZE_ON_CHARGE,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            )
    }
}
