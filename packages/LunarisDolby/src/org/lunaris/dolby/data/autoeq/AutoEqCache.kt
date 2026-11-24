/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AutoEqCache(context: Context) {
    private val baseDir = File(context.filesDir, "autoeq").apply { mkdirs() }
    private val profilesDir = File(baseDir, "profiles").apply { mkdirs() }

    suspend fun saveMetadata(metadata: AutoEqMetadata) = withContext(Dispatchers.IO) {
        File(baseDir, "metadata.json").writeText(AutoEqMetadata.toJson(metadata))
    }

    suspend fun loadMetadata(): AutoEqMetadata? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "metadata.json")
        if (file.exists()) AutoEqMetadata.fromJson(file.readText()) else null
    }

    suspend fun saveIndex(index: AutoEqIndex) = withContext(Dispatchers.IO) {
        File(baseDir, "index.json").writeText(AutoEqIndex.toJson(index))
    }

    suspend fun loadIndex(): AutoEqIndex? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "index.json")
        if (file.exists()) AutoEqIndex.fromJson(file.readText()) else null
    }

    suspend fun saveProfile(profile: AutoEqProfile) = withContext(Dispatchers.IO) {
        profileFile(profile.id).writeText(AutoEqProfile.toJson(profile))
    }

    suspend fun loadProfile(id: String): AutoEqProfile? = withContext(Dispatchers.IO) {
        val file = profileFile(id)
        if (file.exists()) AutoEqProfile.fromJson(file.readText()) else null
    }

    private fun profileFile(id: String): File {
        val file = File(profilesDir, "${AutoEqIds.requireValid(id)}.json")
        if (file.canonicalFile.parentFile != profilesDir.canonicalFile) {
            throw IllegalArgumentException("AutoEQ profile path outside the cache")
        }
        return file
    }
}
