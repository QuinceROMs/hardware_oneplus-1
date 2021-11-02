/*
 * Copyright (C) 2021-2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.doze

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.*

class DozeSettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

    private var pickUpPreference: ListPreference? = null
    private var pocketPreference: SwitchPreference? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.doze_settings, rootKey)

        val dozeEnabled = Utils.isDozeEnabled(requireContext())

        val pickupSensorCategory =
            preferenceScreen.findPreference<PreferenceCategory>(Utils.CATEGORY_PICKUP_SENSOR)!!
        if (getString(R.string.pickup_sensor_type).isEmpty()) {
            preferenceScreen.removePreference(pickupSensorCategory)
        }

        val proximitySensorCategory =
            preferenceScreen.findPreference<PreferenceCategory>(Utils.CATEGORY_PROXIMITY_SENSOR)!!
        if (getString(R.string.pocket_sensor_type).isEmpty()) {
            preferenceScreen.removePreference(proximitySensorCategory)
        }

        pickUpPreference = findPreference(Utils.GESTURE_PICK_UP_KEY)
        pickUpPreference?.isEnabled = if (Utils.alwaysOnDisplayAvailable(requireContext())) {
            !Utils.isAlwaysOnEnabled(requireContext()) && dozeEnabled
        } else {
            dozeEnabled
        }
        pickUpPreference?.onPreferenceChangeListener = this

        pocketPreference = findPreference(Utils.GESTURE_POCKET_KEY)
        pocketPreference?.isEnabled = if (Utils.alwaysOnDisplayAvailable(requireContext())) {
            !Utils.isAlwaysOnEnabled(requireContext()) && dozeEnabled
        } else {
            dozeEnabled
        }
        pocketPreference?.onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        handler.post { Utils.checkDozeService(requireContext()) }
        return true
    }
}
