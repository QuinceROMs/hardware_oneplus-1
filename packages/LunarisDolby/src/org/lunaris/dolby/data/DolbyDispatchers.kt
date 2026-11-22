/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Dispatchers backing DolbyRepository access.
 *
 * Every DolbyAudioEffect handle attaches to the shared global output session, and
 * checkEffect() can release and recreate that handle in place. Concurrent callers
 * would race the swap, so all repository reads and writes run on a single
 * serialized worker. This keeps the existing serialization the UI thread provided
 * while moving the binder round-trips off it.
 */
object DolbyDispatchers {
    @OptIn(ExperimentalCoroutinesApi::class)
    val hal: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
