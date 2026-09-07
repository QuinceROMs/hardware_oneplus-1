/*
 * Copyright (C) 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.doze

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors

class PickupSensor(
    private val context: Context,
    sensorType: String,
    private val sensorValue: Float,
) : SensorEventListener {
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)

    private val sensorManager = context.getSystemService(SensorManager::class.java)!!
    private val sensor = Utils.getSensor(sensorManager, sensorType)

    private val executorService = Executors.newSingleThreadExecutor()
    private var entryTimestamp = 0L
    private var enabled = false

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            val value = event.values[0]
            executorService.execute {
                if (!enabled) return@execute
                handleSensorValue(value)
                enabled = registerSensor()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values[0]
        executorService.execute {
            if (enabled) handleSensorValue(value)
        }
    }

    private fun handleSensorValue(value: Float) {
        if (DEBUG) Log.d(TAG, "Got sensor event: $value")
        val delta = SystemClock.elapsedRealtime() - entryTimestamp
        if (delta < MIN_PULSE_INTERVAL_MS) {
            return
        }
        entryTimestamp = SystemClock.elapsedRealtime()
        if (value == sensorValue) {
            if (Utils.isPickUpSetToWake(context)) {
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
                powerManager.wakeUpWithProximityCheck(
                    SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_GESTURE,
                    TAG,
                    Display.DEFAULT_DISPLAY,
                )
            } else {
                Utils.launchDozePulse(context)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun registerSensor(): Boolean {
        val pickupSensor = sensor ?: return false
        val registered = if (pickupSensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            sensorManager.requestTriggerSensor(triggerListener, pickupSensor)
        } else {
            sensorManager.registerListener(this, pickupSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (!registered) Log.w(TAG, "Unable to register pickup sensor")
        return registered
    }

    fun enable() {
        if (sensor == null) return
        executorService.execute {
            if (enabled) return@execute
            Log.d(TAG, "Enabling")
            entryTimestamp = SystemClock.elapsedRealtime()
            enabled = registerSensor()
        }
    }

    fun disable() {
        if (sensor == null) return
        executorService.execute {
            if (!enabled) return@execute
            Log.d(TAG, "Disabling")
            enabled = false
            if (sensor?.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                sensorManager.cancelTriggerSensor(triggerListener, sensor)
            } else {
                sensorManager.unregisterListener(this, sensor)
            }
        }
    }

    companion object {
        private const val TAG = "PickupSensor"
        private const val DEBUG = false

        private const val MIN_PULSE_INTERVAL_MS = 2500L
        private const val WAKELOCK_TIMEOUT_MS = 300L
    }
}
