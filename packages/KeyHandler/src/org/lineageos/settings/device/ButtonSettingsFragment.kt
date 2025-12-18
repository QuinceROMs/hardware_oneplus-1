/*
 * SPDX-FileCopyrightText: 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class ButtonSettingsFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {
    private lateinit var sliderDozeSwitch: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.button_panel, rootKey)

        sliderDozeSwitch = findPreference<SwitchPreferenceCompat>(KeyHandler.SLIDER_DOZE_ENABLED)!!
        sliderDozeSwitch.isChecked = KeyHandler.isSliderDozeEnabled(requireContext())
        sliderDozeSwitch.onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (preference == sliderDozeSwitch) {
            val enabled = newValue as Boolean
            Settings.System.putInt(context!!.contentResolver,
                    KeyHandler.SLIDER_DOZE_ENABLED_SETTING, if (enabled) 1 else 0)
            return true
        }
        return false
    }

    companion object {

    } 
}