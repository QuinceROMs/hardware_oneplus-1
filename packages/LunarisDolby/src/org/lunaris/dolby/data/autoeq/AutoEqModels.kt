/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "AutoEqModels"

/**
 * Profile ids come from the network and become file names and URL path segments, so they are
 * checked against a strict pattern instead of being sanitised. The ids in the upstream index use
 * only lowercase letters, digits and underscores.
 */
object AutoEqIds {
    private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    fun requireValid(id: String): String {
        if (!isValid(id)) throw IllegalArgumentException("Invalid AutoEQ profile id")
        return id
    }

    fun fromJson(obj: JSONObject): String {
        val id = obj.getString("id")
        if (!isValid(id)) throw JSONException("Invalid AutoEQ profile id")
        return id
    }
}

data class AutoEqMetadata(
    val version: Int,
    val profileCount: Int,
    val indexHash: String,
    val timestamp: Long? = null
) {
    companion object {
        fun fromJson(json: String): AutoEqMetadata {
            val obj = JSONObject(json)
            return AutoEqMetadata(
                version = obj.getInt("version"),
                profileCount = obj.getInt("profileCount"),
                indexHash = obj.getString("indexHash"),
                timestamp = if (obj.has("timestamp")) obj.getLong("timestamp") else null
            )
        }
        fun toJson(data: AutoEqMetadata): String {
            val obj = JSONObject()
            obj.put("version", data.version)
            obj.put("profileCount", data.profileCount)
            obj.put("indexHash", data.indexHash)
            data.timestamp?.let { obj.put("timestamp", it) }
            return obj.toString()
        }
    }
}

data class IndexEntry(
    val id: String,
    val name: String,
    val source: String,
    val measurementRig: String,
    val searchKey: String
) {
    companion object {
        fun fromJson(obj: JSONObject): IndexEntry {
            return IndexEntry(
                id = AutoEqIds.fromJson(obj),
                name = obj.getString("name"),
                source = obj.optString("source", "Unknown"),
                measurementRig = obj.optString("measurementRig", "Unknown"),
                searchKey = obj.getString("searchKey")
            )
        }
        
        fun toJson(data: IndexEntry): JSONObject {
            val obj = JSONObject()
            obj.put("id", data.id)
            obj.put("name", data.name)
            obj.put("source", data.source)
            obj.put("measurementRig", data.measurementRig)
            obj.put("searchKey", data.searchKey)
            return obj
        }
    }
}

data class AutoEqProfile(
    val id: String,
    val name: String,
    val graphicEq: String,
    val preamp: Float? = null
) {
    companion object {
        fun fromJson(json: String): AutoEqProfile {
            val obj = JSONObject(json)
            return AutoEqProfile(
                id = AutoEqIds.fromJson(obj),
                name = obj.getString("name"),
                graphicEq = obj.getString("graphicEq"),
                preamp = if (obj.has("preamp") && !obj.isNull("preamp")) obj.getDouble("preamp").toFloat() else null
            )
        }
        fun toJson(data: AutoEqProfile): String {
            val obj = JSONObject()
            obj.put("id", data.id)
            obj.put("name", data.name)
            obj.put("graphicEq", data.graphicEq)
            data.preamp?.let { obj.put("preamp", it) }
            return obj.toString()
        }
    }
}

data class AutoEqIndex(
    val version: Int,
    val profiles: List<IndexEntry>
) {
    companion object {
        fun fromJson(json: String): AutoEqIndex {
            val obj = JSONObject(json)
            val profilesArray = obj.getJSONArray("profiles")
            val profiles = mutableListOf<IndexEntry>()
            for (i in 0 until profilesArray.length()) {
                val entry = profilesArray.getJSONObject(i)
                if (!AutoEqIds.isValid(entry.optString("id"))) {
                    Log.w(TAG, "Skipping index entry $i with an invalid id")
                    continue
                }
                profiles.add(IndexEntry.fromJson(entry))
            }
            return AutoEqIndex(
                version = obj.getInt("version"),
                profiles = profiles
            )
        }
        fun toJson(data: AutoEqIndex): String {
            val obj = JSONObject()
            obj.put("version", data.version)
            val profilesArray = JSONArray()
            data.profiles.forEach { profilesArray.put(IndexEntry.toJson(it)) }
            obj.put("profiles", profilesArray)
            return obj.toString()
        }
    }
}
