/*
 * Copyright (C) 2021-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.doze

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class DozeService : Service() {
    private lateinit var pickupSensor: PickupSensor
    private lateinit var pocketSensor: PocketSensor
    private var pickupEnabled = false
    private var pocketEnabled = false

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> onDisplayOn()
                    Intent.ACTION_SCREEN_OFF -> onDisplayOff()
                }
            }
        }

    override fun onCreate() {
        Log.d(TAG, "Creating service")
        pickupSensor =
            PickupSensor(
                this,
                resources.getString(R.string.pickup_sensor_type),
                resources.getFloat(R.dimen.pickup_sensor_value),
            )
        pocketSensor =
            PocketSensor(
                this,
                resources.getString(R.string.pocket_sensor_type),
                resources.getFloat(R.dimen.pocket_sensor_value),
            )

        val screenStateFilter = IntentFilter()
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON)
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenStateReceiver, screenStateFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (getSystemService(PowerManager::class.java)!!.isInteractive) {
            onDisplayOn()
        } else {
            onDisplayOff()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(screenStateReceiver)
        pickupSensor.disable()
        pocketSensor.disable()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onDisplayOn() = updateSensors(interactive = true)

    private fun onDisplayOff() = updateSensors(interactive = false)

    private fun updateSensors(interactive: Boolean) {
        val enablePickup = !interactive && Utils.isPickUpEnabled(this)
        val enablePocket = !interactive && Utils.isPocketEnabled(this)
        if (pickupEnabled != enablePickup) {
            if (enablePickup) pickupSensor.enable() else pickupSensor.disable()
            pickupEnabled = enablePickup
        }
        if (pocketEnabled != enablePocket) {
            if (enablePocket) pocketSensor.enable() else pocketSensor.disable()
            pocketEnabled = enablePocket
        }
    }

    companion object {
        private const val TAG = "DozeService"
    }
}
