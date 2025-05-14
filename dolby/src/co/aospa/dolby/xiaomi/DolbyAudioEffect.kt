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
        val buf = ByteArray(12)
        int32ToByteArray(param, buf, 0)
        int32ToByteArray(1, buf, 4)
        int32ToByteArray(value, buf, 8)
        checkStatus(setParameter(EFFECT_PARAM_CPDP_VALUES, buf))
    }

    private fun getIntParam(param: Int): Int {
        val buf = ByteArray(12)
        int32ToByteArray(param, buf, 0)
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
        val buf = ByteArray((values.size + 4) * 4)
        int32ToByteArray(EFFECT_PARAM_SET_PROFILE_PARAMETER, buf, 0)
        int32ToByteArray(values.size + 1, buf, 4)
        int32ToByteArray(profile, buf, 8)
        int32ToByteArray(param.id, buf, 12)
        int32ArrayToByteArray(values, buf, 16)
        checkStatus(setParameter(EFFECT_PARAM_CPDP_VALUES, buf))
    }

    fun setDapParameter(param: DsParam, enable: Boolean, profile: Int = this.profile) =
        setDapParameter(param, intArrayOf(if (enable) 1 else 0), profile)

    fun setDapParameter(param: DsParam, value: Int, profile: Int = this.profile) =
        setDapParameter(param, intArrayOf(value), profile)

    fun getDapParameter(param: DsParam, profile: Int = this.profile): IntArray {
        dlog(TAG, "getDapParameter: profile=$profile param=$param")
        val buf = ByteArray((param.length + 2) * 4)
        val p = (param.id shl 16) + (profile shl 8) + EFFECT_PARAM_GET_PROFILE_PARAMETER
        checkStatus(getParameter(p, buf))
        return byteArrayToInt32Array(buf, param.length)
    }

    fun getDapParameterBool(param: DsParam, profile: Int = this.profile): Boolean =
        getDapParameter(param, profile)[0] == 1

    fun getDapParameterInt(param: DsParam, profile: Int = this.profile): Int =
        getDapParameter(param, profile)[0]

    companion object {
        internal const val TAG = "DolbyAudioEffect"
        internal val EFFECT_TYPE_DAP =
            UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")

        internal const val EFFECT_PARAM_ENABLE = 0
        internal const val EFFECT_PARAM_CPDP_VALUES = 5
        internal const val EFFECT_PARAM_PROFILE = 0xA000000
        internal const val EFFECT_PARAM_SET_PROFILE_PARAMETER = 0x1000000
        internal const val EFFECT_PARAM_GET_PROFILE_PARAMETER = 0x1000005
        internal const val EFFECT_PARAM_RESET_PROFILE_SETTINGS = 0xC000000

        private fun int32ToByteArray(value: Int, dst: ByteArray, index: Int) {
            dst[index] = (value and 0xFF).toByte()
            dst[index + 1] = ((value ushr 8) and 0xFF).toByte()
            dst[index + 2] = ((value ushr 16) and 0xFF).toByte()
            dst[index + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        private fun byteArrayToInt32(ba: ByteArray): Int {
            return (ba[3].toInt() shl 24) or
                    (ba[2].toInt() and 0xFF shl 16) or
                    (ba[1].toInt() and 0xFF shl 8) or
                    (ba[0].toInt() and 0xFF)
        }

        private fun int32ArrayToByteArray(src: IntArray, dst: ByteArray, index: Int) {
            for (i in src.indices) {
                val pos = index + i * 4
                val x = src[i]
                dst[pos] = (x and 0xFF).toByte()
                dst[pos + 1] = ((x ushr 8) and 0xFF).toByte()
                dst[pos + 2] = ((x ushr 16) and 0xFF).toByte()
                dst[pos + 3] = ((x ushr 24) and 0xFF).toByte()
            }
        }

        private fun byteArrayToInt32Array(ba: ByteArray, dstLength: Int): IntArray {
            val dst = IntArray(dstLength.coerceAtMost(ba.size shr 2))
            for (i in dst.indices) {
                val base = i * 4
                dst[i] = (ba[base + 3].toInt() and 0xFF shl 24) or
                        (ba[base + 2].toInt() and 0xFF shl 16) or
                        (ba[base + 1].toInt() and 0xFF shl 8) or
                        (ba[base].toInt() and 0xFF)
            }
            return dst
        }
    }
}
