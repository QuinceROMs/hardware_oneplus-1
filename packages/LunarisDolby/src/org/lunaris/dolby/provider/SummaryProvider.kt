/*
 * Copyright (C) 2019 The Android Open Source Project
 *           (C) 2023-24 Paranoid Android
 *           (C) 2024-2025 Lunaris AOSP
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.R
import org.lunaris.dolby.domain.models.dolbyProfileName

private const val KEY_DOLBY = "dolby"
private const val META_DATA_PREFERENCE_SUMMARY = "com.android.settings.summary"

class SummaryProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val summary = when (method) {
            KEY_DOLBY -> getDolbySummary()
            else -> return null
        }
        return Bundle().apply {
            putString(META_DATA_PREFERENCE_SUMMARY, summary)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    // Runs on a binder thread, so it reads the saved state instead of the effect, which is only
    // accessed on DolbyDispatchers.hal.
    private fun getDolbySummary(): String {
        val context = context ?: return ""
        val prefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)

        if (!prefs.getBoolean(DolbyConstants.PREF_ENABLE, false)) {
            return context.getString(R.string.dolby_off)
        }

        val profile = prefs.getString(DolbyConstants.PREF_PROFILE, null)?.toIntOrNull() ?: 0
        val profileName = dolbyProfileName(context, profile)
        return context.getString(R.string.dolby_on_with_profile, profileName)
    }
}
