/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi

import android.media.audiofx.AudioEffect
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyConstants.DsParam
import java.util.UUID

class DolbyAudioEffect(priority: Int, audioSession: Int) : AudioEffect(
    EFFECT_TYPE_NULL, EFFECT_TYPE_DAP, priority, audioSession
) {
    var dsOn: Boolean
        get() = getIntParam(EFFECT_PARAM_ENABLE) == 1
        set(value) {
            setIntParam(EFFECT_PARAM_ENABLE, if (value) 1 else 0)
            enabled = value
        }

    var profile: Int
        get() = getIntParam(EFFECT_PARAM_PROFILE)
        set(value) {
            setIntParam(EFFECT_PARAM_PROFILE, value)
        }

    private fun setIntParam(param: Int, value: Int) {
        dlog(TAG, "setIntParam($param, $value)")
        val buf = ByteArray(12).apply {
            int32ToByteArray(param, this, 0)
            int32ToByteArray(1, this, 4)
            int32ToByteArray(value, this, 8)
        }
        checkStatus(setParameter(EFFECT_PARAM_CPDP_VALUES, buf))
    }

    private fun getIntParam(param: Int): Int {
        val buf = ByteArray(12).apply {
            int32ToByteArray(param, this, 0)
        }
        checkStatus(getParameter(EFFECT_PARAM_CPDP_VALUES + param, buf))
        return byteArrayToInt32(buf).also {
            dlog(TAG, "getIntParam($param): $it")
        }
    }

    fun resetProfileSpecificSettings(profile: Int = this.profile) {
        dlog(TAG, "resetProfileSpecificSettings: profile=$profile")
        setIntParam(EFFECT_PARAM_RESET_PROFILE_SETTINGS, profile)
    }

    fun setDapParameter(param: DsParam, values: IntArray, profile: Int = this.profile) {
        dlog(TAG, "setDapParameter: profile=$profile param=$param")
        ByteArray((values.size + 4) * 4).apply {
            int32ToByteArray(EFFECT_PARAM_SET_PROFILE_PARAMETER, this, 0)
            int32ToByteArray(values.size + 1, this, 4)
            int32ToByteArray(profile, this, 8)
            int32ToByteArray(param.id, this, 12)
            int32ArrayToByteArray(values, this, 16)
            checkStatus(setParameter(EFFECT_PARAM_CPDP_VALUES, this))
        }
    }

    fun setDapParameter(param: DsParam, enable: Boolean, profile: Int = this.profile) {
        setDapParameter(param, intArrayOf(if (enable) 1 else 0), profile)
    }

    fun setDapParameter(param: DsParam, value: Int, profile: Int = this.profile) {
        setDapParameter(param, intArrayOf(value), profile)
    }

    fun getDapParameter(param: DsParam, profile: Int = this.profile): IntArray {
        dlog(TAG, "getDapParameter: profile=$profile param=$param")
        return ByteArray((param.length + 2) * 4).let { buf ->
            val p = (param.id shl 16) or (profile shl 8) or EFFECT_PARAM_GET_PROFILE_PARAMETER
            checkStatus(getParameter(p, buf))
            byteArrayToInt32Array(buf, param.length)
        }
    }

    fun getDapParameterBool(param: DsParam, profile: Int = this.profile): Boolean {
        return getDapParameter(param, profile)[0] == 1
    }

    fun getDapParameterInt(param: DsParam, profile: Int = this.profile): Int {
        return getDapParameter(param, profile)[0]
    }

    companion object {
        private const val TAG = "DolbyAudioEffect"
        private val EFFECT_TYPE_DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")

        private const val EFFECT_PARAM_ENABLE = 0
        private const val EFFECT_PARAM_CPDP_VALUES = 5
        private const val EFFECT_PARAM_PROFILE = 0xA000000
        private const val EFFECT_PARAM_SET_PROFILE_PARAMETER = 0x1000000
        private const val EFFECT_PARAM_GET_PROFILE_PARAMETER = 0x1000005
        private const val EFFECT_PARAM_RESET_PROFILE_SETTINGS = 0xC000000

        private fun int32ToByteArray(value: Int, dst: ByteArray, index: Int) {
            var idx = index
            dst[idx++] = (value and 0xFF).toByte()
            dst[idx++] = (value ushr 8 and 0xFF).toByte()
            dst[idx++] = (value ushr 16 and 0xFF).toByte()
            dst[idx] = (value ushr 24 and 0xFF).toByte()
        }

        private fun byteArrayToInt32(ba: ByteArray): Int {
            return (ba[3].toInt() and 0xFF shl 24) or
                   (ba[2].toInt() and 0xFF shl 16) or
                   (ba[1].toInt() and 0xFF shl 8) or
                   (ba[0].toInt() and 0xFF)
        }

        private fun int32ArrayToByteArray(src: IntArray, dst: ByteArray, index: Int) {
            var idx = index
            src.forEach { x ->
                dst[idx++] = (x and 0xFF).toByte()
                dst[idx++] = (x ushr 8 and 0xFF).toByte()
                dst[idx++] = (x ushr 16 and 0xFF).toByte()
                dst[idx++] = (x ushr 24 and 0xFF).toByte()
            }
        }

        private fun byteArrayToInt32Array(ba: ByteArray, dstLength: Int): IntArray {
            return IntArray(minOf(dstLength, ba.size / 4)).apply {
                forEachIndexed { i, _ ->
                    val offset = i * 4
                    this[i] = (ba[offset + 3].toInt() and 0xFF shl 24) or
                              (ba[offset + 2].toInt() and 0xFF shl 16) or
                              (ba[offset + 1].toInt() and 0xFF shl 8) or
                              (ba[offset].toInt() and 0xFF)
                }
            }
        }
    }
}
