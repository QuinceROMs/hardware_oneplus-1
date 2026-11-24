/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AutoEqRepository(context: Context) {
    private val cache = AutoEqCache(context)
    private val api = AutoEqApi(AutoEqDownloader(context))
    
    private val memoryCache = LruCache<String, AutoEqProfile>(50)
    private var cachedIndex: List<IndexEntry> = emptyList()
    private val downloadMutex = Mutex()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        cachedIndex = cache.loadIndex()?.profiles ?: emptyList()
        
        downloadMutex.withLock {
            val localMeta = cache.loadMetadata()
            val remoteMetaString = api.getMetadata()
            
            if (remoteMetaString != null) {
                val remoteMeta = AutoEqMetadata.fromJson(remoteMetaString)
                
                if (localMeta?.indexHash != remoteMeta.indexHash || cachedIndex.isEmpty()) {
                    val remoteIndexString = api.getIndex()
                    if (remoteIndexString != null) {
                        val newIndex = AutoEqIndex.fromJson(remoteIndexString)
                        cache.saveIndex(newIndex)
                        cache.saveMetadata(remoteMeta)
                        cachedIndex = newIndex.profiles
                    }
                }
            }
        }
    }

    fun search(query: String): List<IndexEntry> {
        return AutoEqSearch.search(query, cachedIndex)
    }

    suspend fun getProfile(id: String): AutoEqProfile? = withContext(Dispatchers.IO) {
        AutoEqIds.requireValid(id)
        memoryCache.get(id)?.let { return@withContext it }
        
        val diskProfile = cache.loadProfile(id)
        if (diskProfile != null) {
            memoryCache.put(id, diskProfile)
            return@withContext diskProfile
        }
        
        downloadMutex.withLock {
            val remoteString = api.getProfile(id) ?: return@withContext null
            val profile = AutoEqProfile.fromJson(remoteString)
            // The file is named after the id inside the profile, which must be the one requested.
            if (profile.id != id) throw IllegalArgumentException("AutoEQ profile id mismatch")
            
            cache.saveProfile(profile)
            memoryCache.put(id, profile)
            
            return@withContext profile
        }
    }
}
