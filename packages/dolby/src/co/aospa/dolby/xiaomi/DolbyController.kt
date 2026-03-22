/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyConstants.DsParam
import co.aospa.dolby.xiaomi.R

internal class DolbyController private constructor(
    private val context: Context
) {
    private val effectLock = Any()
    private val playbackEffects = mutableMapOf<Int, DolbyAudioEffect>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(context.mainLooper)
    @Volatile
    private var desiredDsOn = true
    @Volatile
    private var desiredProfile = DEFAULT_PROFILE

    private data class PlaybackSessionDecision(
        val sessionId: Int,
        val bypassReason: String? = null
    ) {
        val shouldAttachEffect: Boolean
            get() = bypassReason == null
    }

    // Keep settings in preferences until there is active playback to avoid
    // creating a session-0 control effect before route selection finishes.
    private val playbackCallback = object : AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val sessionIds = collectAttachablePlaybackSessionIds(configs)
            dlog(TAG, "onPlaybackConfigChanged: attachableSessionIds=$sessionIds")
            syncPlaybackEffects(sessionIds)
        }
    }

    // Restore current profile on audio device change
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesAdded")
            setCurrentProfile()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesRemoved")
            setCurrentProfile()
        }
    }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager!!.registerAudioPlaybackCallback(playbackCallback, handler)
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            } else {
                audioManager!!.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }

    var dsOn: Boolean
        get() = desiredDsOn.also {
            dlog(TAG, "getDsOn: $it")
        }
        set(value) {
            dlog(TAG, "setDsOn: $value")
            desiredDsOn = value
            registerCallbacks = value
            if (value) {
                syncPlaybackEffects(getActivePlaybackSessionIds())
            } else {
                releasePlaybackEffects()
            }
        }

    var profile: Int
        get() = desiredProfile.also {
            dlog(TAG, "getProfile: $it")
        }
        set(value) {
            dlog(TAG, "setProfile: $value")
            desiredProfile = value
            updateEffects { effect ->
                effect.profile = value
                ensureStoredProfileSettings(effect, value)
                applyStoredProfileSettings(effect, value)
            }
        }

    init {
        loadStateFromPreferences()
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        loadStateFromPreferences()
        dlog(TAG, "onBootCompleted: dsOn=$desiredDsOn profile=$desiredProfile")
        registerCallbacks = desiredDsOn
        syncPlaybackEffects(getActivePlaybackSessionIds())
    }

    private fun loadStateFromPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        desiredDsOn = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)
        desiredProfile = prefs.getString(
            DolbyConstants.PREF_PROFILE,
            DEFAULT_PROFILE.toString()
        )!!.toInt()
    }

    private fun getProfilePreferences(profile: Int) =
        context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)

    private fun getDefaultPreset(): String =
        context.resources.getStringArray(R.array.dolby_preset_values).first()

    private fun getDefaultIeqPreset(): Int =
        context.resources.getStringArray(R.array.dolby_ieq_values).first().toInt()

    private fun getDefaultStereoWideningAmount(): Int =
        context.resources.getStringArray(R.array.dolby_stereo_values).first().toInt()

    private fun getDefaultDialogueEnhancerAmount(): Int =
        context.resources.getStringArray(R.array.dolby_dialogue_values).first().toInt()

    private fun getStoredString(profile: Int, key: String, defaultValue: String): String {
        val value = getProfilePreferences(profile).all[key] ?: return defaultValue
        return value as? String ?: error("Unexpected value type for $key: ${value::class.java.name}")
    }

    private fun getStoredInt(profile: Int, key: String, defaultValue: Int): Int {
        val value = getProfilePreferences(profile).all[key] ?: return defaultValue
        return when (value) {
            is Int -> value
            is String -> value.toInt()
            else -> error("Unexpected value type for $key: ${value::class.java.name}")
        }
    }

    private fun getStoredBoolean(profile: Int, key: String, defaultValue: Boolean): Boolean {
        val value = getProfilePreferences(profile).all[key] ?: return defaultValue
        return value as? Boolean ?: error("Unexpected value type for $key: ${value::class.java.name}")
    }

    private fun getStoredPreset(profile: Int): String =
        getStoredString(profile, DolbyConstants.PREF_PRESET, getDefaultPreset())

    private fun getStoredIeqPreset(profile: Int): Int =
        getStoredInt(profile, DolbyConstants.PREF_IEQ, getDefaultIeqPreset())

    private fun getStoredHeadphoneVirtEnabled(profile: Int): Boolean =
        getStoredBoolean(profile, DolbyConstants.PREF_HP_VIRTUALIZER, false)

    private fun getStoredSpeakerVirtEnabled(profile: Int): Boolean =
        getStoredBoolean(profile, DolbyConstants.PREF_SPK_VIRTUALIZER, false)

    private fun getStoredStereoWideningAmount(profile: Int): Int =
        getStoredInt(profile, DolbyConstants.PREF_STEREO, getDefaultStereoWideningAmount())

    private fun getStoredDialogueEnhancerAmount(profile: Int): Int =
        getStoredInt(profile, DolbyConstants.PREF_DIALOGUE, getDefaultDialogueEnhancerAmount())

    private fun getStoredBassEnhancerEnabled(profile: Int): Boolean =
        getStoredBoolean(profile, DolbyConstants.PREF_BASS, false)

    private fun getStoredVolumeLevelerEnabled(profile: Int): Boolean =
        getStoredBoolean(profile, DolbyConstants.PREF_VOLUME, false)

    private fun persistStoredString(profile: Int, key: String, value: String) {
        getProfilePreferences(profile).edit()
            .putString(key, value)
            .apply()
    }

    private fun persistStoredBoolean(profile: Int, key: String, value: Boolean) {
        getProfilePreferences(profile).edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun clearStoredProfileSettings(profile: Int) {
        getProfilePreferences(profile).edit().clear().apply()
    }

    private fun parseStoredPreset(value: String): IntArray = value.split(",")
        .map { it.toInt() }
        .toIntArray()

    private fun storeProfileSettingsFromEffect(
        effect: DolbyAudioEffect,
        profile: Int,
        missingOnly: Boolean = false
    ) {
        val prefs = getProfilePreferences(profile)
        val editor = prefs.edit()
        var changed = false

        fun putStringIfNeeded(key: String, valueProvider: () -> String) {
            if (!missingOnly || !prefs.contains(key)) {
                editor.putString(key, valueProvider())
                changed = true
            }
        }

        fun putBooleanIfNeeded(key: String, valueProvider: () -> Boolean) {
            if (!missingOnly || !prefs.contains(key)) {
                editor.putBoolean(key, valueProvider())
                changed = true
            }
        }

        putStringIfNeeded(DolbyConstants.PREF_PRESET) {
            effect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile).joinToString(separator = ",")
        }
        putStringIfNeeded(DolbyConstants.PREF_IEQ) {
            effect.getDapParameterInt(DsParam.IEQ_PRESET, profile).toString()
        }
        putBooleanIfNeeded(DolbyConstants.PREF_HP_VIRTUALIZER) {
            effect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile)
        }
        putBooleanIfNeeded(DolbyConstants.PREF_SPK_VIRTUALIZER) {
            effect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile)
        }
        putStringIfNeeded(DolbyConstants.PREF_STEREO) {
            effect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile).toString()
        }
        putStringIfNeeded(DolbyConstants.PREF_DIALOGUE) {
            val enabled = effect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
            val amount = if (enabled) {
                effect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
            } else {
                0
            }
            amount.toString()
        }
        putBooleanIfNeeded(DolbyConstants.PREF_BASS) {
            effect.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile)
        }
        putBooleanIfNeeded(DolbyConstants.PREF_VOLUME) {
            effect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile)
        }

        if (changed) {
            editor.apply()
        }
    }

    private fun ensureStoredProfileSettings(effect: DolbyAudioEffect, profile: Int) {
        storeProfileSettingsFromEffect(effect, profile, missingOnly = true)
    }

    private fun applyStoredProfileSettings(effect: DolbyAudioEffect, profile: Int) {
        effect.setDapParameter(DsParam.GEQ_BAND_GAINS, parseStoredPreset(getStoredPreset(profile)), profile)
        effect.setDapParameter(DsParam.IEQ_PRESET, getStoredIeqPreset(profile), profile)
        effect.setDapParameter(
            DsParam.HEADPHONE_VIRTUALIZER,
            getStoredHeadphoneVirtEnabled(profile),
            profile
        )
        effect.setDapParameter(
            DsParam.SPEAKER_VIRTUALIZER,
            getStoredSpeakerVirtEnabled(profile),
            profile
        )
        effect.setDapParameter(
            DsParam.STEREO_WIDENING_AMOUNT,
            getStoredStereoWideningAmount(profile),
            profile
        )
        val dialogueAmount = getStoredDialogueEnhancerAmount(profile)
        effect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, (dialogueAmount > 0), profile)
        effect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, dialogueAmount, profile)
        effect.setDapParameter(
            DsParam.BASS_ENHANCER_ENABLE,
            getStoredBassEnhancerEnabled(profile),
            profile
        )
        effect.setDapParameter(
            DsParam.VOLUME_LEVELER_ENABLE,
            getStoredVolumeLevelerEnabled(profile),
            profile
        )
    }

    private inline fun <T> withPlaybackEffect(block: (DolbyAudioEffect) -> T): T? =
        synchronized(effectLock) {
            playbackEffects.values.firstOrNull { it.hasControl() }?.let(block)
        }

    private fun getPlaybackDecision(config: AudioPlaybackConfiguration): PlaybackSessionDecision? {
        if (config.playerState != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
            return null
        }
        val sessionId = config.sessionId
        if (sessionId <= 0) {
            return null
        }
        return when (config.audioAttributes.usage) {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME -> PlaybackSessionDecision(
                sessionId = sessionId,
                bypassReason = getPlaybackBypassReason(config)
            )
            else -> null
        }
    }

    private fun getPlaybackBypassReason(config: AudioPlaybackConfiguration): String? {
        val attributes = config.audioAttributes
        val flags = attributes.allFlags
        if ((flags and AudioAttributes.FLAG_HW_AV_SYNC) != 0) {
            return "hw_av_sync"
        }
        if ((flags and AudioAttributes.FLAG_LOW_LATENCY) != 0) {
            return "low_latency"
        }
        return null
    }

    private fun collectAttachablePlaybackSessionIds(
        configs: List<AudioPlaybackConfiguration>
    ): Set<Int> {
        val attachableSessionIds = LinkedHashSet<Int>()
        val bypassedSessions = mutableListOf<String>()
        configs.forEach { config ->
            val decision = getPlaybackDecision(config) ?: return@forEach
            if (decision.shouldAttachEffect) {
                attachableSessionIds += decision.sessionId
            } else {
                bypassedSessions += buildPlaybackDebugSummary(
                    config = config,
                    reason = decision.bypassReason!!
                )
            }
        }
        if (bypassedSessions.isNotEmpty()) {
            dlog(TAG, "collectAttachablePlaybackSessionIds: bypassed=${bypassedSessions.joinToString()}")
        }
        return attachableSessionIds
    }

    private fun buildPlaybackDebugSummary(
        config: AudioPlaybackConfiguration,
        reason: String
    ): String {
        val attributes = config.audioAttributes
        return "session=${config.sessionId}" +
            "/usage=${attributes.usage}" +
            "/flags=0x${attributes.allFlags.toString(16)}" +
            "/sampleRate=${config.sampleRate}" +
            "/channelMask=0x${config.channelMask.toString(16)}" +
            "/spatialized=${config.isSpatialized}" +
            "/reason=$reason"
    }

    private fun getActivePlaybackSessionIds(): Set<Int> =
        collectAttachablePlaybackSessionIds(audioManager?.activePlaybackConfigurations.orEmpty())

    private fun createPlaybackEffect(sessionId: Int): DolbyAudioEffect? {
        var effect: DolbyAudioEffect? = null
        return try {
            DolbyAudioEffect(EFFECT_PRIORITY, audioSession = sessionId).also { createdEffect ->
                effect = createdEffect
                createdEffect.profile = desiredProfile
                createdEffect.dsOn = false
                ensureStoredProfileSettings(createdEffect, desiredProfile)
                applyStoredProfileSettings(createdEffect, desiredProfile)
                createdEffect.dsOn = true
            }
        } catch (e: RuntimeException) {
            try {
                effect?.release()
            } catch (releaseError: RuntimeException) {
                Log.w(
                    TAG,
                    "Failed to release Dolby effect after setup failure for session $sessionId",
                    releaseError
                )
            }
            Log.e(TAG, "Failed to create Dolby effect for session $sessionId", e)
            null
        }
    }

    private fun releasePlaybackEffectsLocked() {
        playbackEffects.values.forEach { it.release() }
        playbackEffects.clear()
    }

    private fun releasePlaybackEffects() = synchronized(effectLock) {
        releasePlaybackEffectsLocked()
    }

    private fun syncPlaybackEffects(sessionIds: Set<Int>) {
        dlog(TAG, "syncPlaybackEffects: dsOn=$desiredDsOn sessionIds=$sessionIds")
        synchronized(effectLock) {
            if (!desiredDsOn) {
                releasePlaybackEffectsLocked()
                return
            }

            val staleSessions = playbackEffects.keys - sessionIds
            staleSessions.forEach { sessionId ->
                playbackEffects.remove(sessionId)?.release()
            }

            sessionIds.forEach { sessionId ->
                val effect = playbackEffects[sessionId]
                if (effect?.hasControl() == true) {
                    effect.profile = desiredProfile
                    if (!effect.dsOn) {
                        ensureStoredProfileSettings(effect, desiredProfile)
                        applyStoredProfileSettings(effect, desiredProfile)
                        effect.dsOn = true
                    }
                } else {
                    effect?.release()
                    createPlaybackEffect(sessionId)?.let { playbackEffects[sessionId] = it }
                }
            }
        }
    }

    private inline fun updateEffects(block: (DolbyAudioEffect) -> Unit) {
        var updated = false
        synchronized(effectLock) {
            playbackEffects.values.forEach { effect ->
                if (!effect.hasControl()) return@forEach
                block(effect)
                updated = true
            }
        }
        if (!updated) {
            dlog(TAG, "updateEffects: no active playback effect, stored value will apply later")
        }
    }

    private fun setCurrentProfile() {
        dlog(TAG, "setCurrentProfile")
        loadStateFromPreferences()
        profile = desiredProfile
    }

    fun setDsOnAndPersist(dsOn: Boolean) {
        this.dsOn = dsOn
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(DolbyConstants.PREF_ENABLE, dsOn)
            .apply()
    }

    fun getProfileName(): String? {
        val profile = desiredProfile.toString()
        val profiles = context.resources.getStringArray(R.array.dolby_profile_values)
        val profileIndex = profiles.indexOf(profile)
        dlog(TAG, "getProfileName: profile=$profile index=$profileIndex")
        return if (profileIndex == -1) null else context.resources.getStringArray(
            R.array.dolby_profile_entries
        )[profileIndex]
    }

    fun resetProfileSpecificSettings() {
        dlog(TAG, "resetProfileSpecificSettings")
        val currentProfile = profile
        val resetApplied = synchronized(effectLock) {
            var resetEffect: DolbyAudioEffect? = null
            playbackEffects.values.forEach { effect ->
                if (!effect.hasControl()) return@forEach
                effect.resetProfileSpecificSettings(currentProfile)
                if (resetEffect == null) {
                    resetEffect = effect
                }
            }
            resetEffect?.let {
                storeProfileSettingsFromEffect(it, currentProfile)
                true
            } ?: false
        }
        if (!resetApplied) {
            clearStoredProfileSettings(currentProfile)
        }
    }

    fun getPreset(profile: Int = this.profile): String {
        val preset = withPlaybackEffect { effect ->
            effect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile).joinToString(separator = ",")
        } ?: getStoredPreset(profile)
        dlog(TAG, "getPreset: $preset")
        return preset
    }

    fun setPreset(value: String, profile: Int = this.profile) {
        dlog(TAG, "setPreset: $value")
        val gains = parseStoredPreset(value)
        updateEffects { it.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile) }
        persistStoredString(profile, DolbyConstants.PREF_PRESET, value)
    }

    fun getPresetName(): String {
        val presets = context.resources.getStringArray(R.array.dolby_preset_values)
        val presetIndex = presets.indexOf(getPreset())
        return if (presetIndex == -1) {
            "Custom"
        } else {
            context.resources.getStringArray(
                R.array.dolby_preset_entries
            )[presetIndex]
        }
    }

    fun getHeadphoneVirtEnabled(profile: Int = this.profile): Boolean {
        val enabled = withPlaybackEffect {
            it.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile)
        } ?: getStoredHeadphoneVirtEnabled(profile)
        dlog(TAG, "getHeadphoneVirtEnabled: $enabled")
        return enabled
    }

    fun setHeadphoneVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setHeadphoneVirtEnabled: $value")
        updateEffects { it.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, profile) }
        persistStoredBoolean(profile, DolbyConstants.PREF_HP_VIRTUALIZER, value)
    }

    fun getSpeakerVirtEnabled(profile: Int = this.profile): Boolean {
        val enabled = withPlaybackEffect {
            it.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile)
        } ?: getStoredSpeakerVirtEnabled(profile)
        dlog(TAG, "getSpeakerVirtEnabled: $enabled")
        return enabled
    }

    fun setSpeakerVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setSpeakerVirtEnabled: $value")
        updateEffects { it.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, value, profile) }
        persistStoredBoolean(profile, DolbyConstants.PREF_SPK_VIRTUALIZER, value)
    }

    fun getBassEnhancerEnabled(profile: Int = this.profile): Boolean {
        val enabled = withPlaybackEffect {
            it.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile)
        } ?: getStoredBassEnhancerEnabled(profile)
        dlog(TAG, "getBassEnhancerEnabled: $enabled")
        return enabled
    }

    fun setBassEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setBassEnhancerEnabled: $value")
        updateEffects { it.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, profile) }
        persistStoredBoolean(profile, DolbyConstants.PREF_BASS, value)
    }

    fun getVolumeLevelerEnabled(profile: Int = this.profile): Boolean {
        val enabled = withPlaybackEffect {
            it.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile)
        } ?: getStoredVolumeLevelerEnabled(profile)
        dlog(TAG, "getVolumeLevelerEnabled: $enabled")
        return enabled
    }

    fun setVolumeLevelerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVolumeLevelerEnabled: $value")
        updateEffects { it.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, profile) }
        persistStoredBoolean(profile, DolbyConstants.PREF_VOLUME, value)
    }

    fun getStereoWideningAmount(profile: Int = this.profile): Int {
        val amount = withPlaybackEffect {
            it.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile)
        } ?: getStoredStereoWideningAmount(profile)
        dlog(TAG, "getStereoWideningAmount: $amount")
        return amount
    }

    fun setStereoWideningAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setStereoWideningAmount: $value")
        updateEffects { it.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, value, profile) }
        persistStoredString(profile, DolbyConstants.PREF_STEREO, value.toString())
    }

    fun getDialogueEnhancerAmount(profile: Int = this.profile): Int {
        val amount = withPlaybackEffect { effect ->
            val enabled = effect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
            if (enabled) {
                effect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
            } else {
                0
            }
        } ?: getStoredDialogueEnhancerAmount(profile)
        dlog(TAG, "getDialogueEnhancerAmount: amount=$amount")
        return amount
    }

    fun setDialogueEnhancerAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerAmount: $value")
        updateEffects {
            it.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, (value > 0), profile)
            it.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, value, profile)
        }
        persistStoredString(profile, DolbyConstants.PREF_DIALOGUE, value.toString())
    }

    fun getIeqPreset(profile: Int = this.profile): Int {
        val preset = withPlaybackEffect {
            it.getDapParameterInt(DsParam.IEQ_PRESET, profile)
        } ?: getStoredIeqPreset(profile)
        dlog(TAG, "getIeqPreset: $preset")
        return preset
    }

    fun setIeqPreset(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setIeqPreset: $value")
        updateEffects { it.setDapParameter(DsParam.IEQ_PRESET, value, profile) }
        persistStoredString(profile, DolbyConstants.PREF_IEQ, value.toString())
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100
        private const val DEFAULT_PROFILE = 0

        @Volatile
        private var instance: DolbyController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: DolbyController(context).also { instance = it }
            }
    }
}
