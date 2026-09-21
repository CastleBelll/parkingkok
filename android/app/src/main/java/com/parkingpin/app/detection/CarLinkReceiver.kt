package com.parkingpin.app.detection

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import com.parkingpin.app.ParkingkokApplication
import com.parkingpin.app.domain.detection.CarLinkPolicy
import com.parkingpin.app.domain.detection.DetectionEvent
import kotlinx.coroutines.launch

/**
 * The car link (docs/05_PARKING_DETECTION_ENGINE.md §3a "The car link").
 *
 * A phone attached to a car is the strongest signal this product can get, and the only one
 * that knows the *moment* the driver left: motion heuristics infer parking minutes later,
 * from absence, while a disconnect is an event. §3a "Platform reality" says Android can
 * observe this from a broadcast receiver in the background and iOS effectively cannot, so
 * this file has no iOS counterpart and the engine must not need one.
 *
 * ### It is an optional signal and nothing depends on it
 * Every row of §3a's main table stands on motion and location alone. With Bluetooth off,
 * `BLUETOOTH_CONNECT` denied, or a car that has no Bluetooth audio at all, detection loses
 * accuracy and loses nothing else — which is also CLAUDE.md's rule that a denied permission
 * is never an app failure.
 *
 * ### Why the permission denial needs no handling here
 * From Android 12 the system does not deliver `ACTION_ACL_CONNECTED` /
 * `ACTION_ACL_DISCONNECTED` to an app without `BLUETOOTH_CONNECT` at all. The failure mode
 * is therefore silence, not an exception, and silence is already the supported case. The
 * `try`/`catch` below covers the narrower window where the broadcast arrives and the grant
 * is revoked before the device's class is read.
 *
 * ### Privacy
 * A device class integer is the only thing read. The name, the alias and the MAC address
 * are never touched, never logged and never reach analytics — docs/09 keeps them on the
 * device, and docs/17 §3 has no field that could carry one. The decision itself lives in
 * [CarLinkPolicy], which by construction cannot see anything else.
 */
class CarLinkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }

        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return
        if (!CarLinkPolicy.isCarAudioDevice(deviceClassOf(device))) return

        val container = ParkingkokApplication.containerOf(context) ?: return
        val atMillis = container.clock.nowEpochMillis()
        val event = if (connected) {
            DetectionEvent.CarLinkConnected(atMillis)
        } else {
            DetectionEvent.CarLinkDisconnected(atMillis)
        }

        // The direction only. Never the device.
        Log.i(TAG, "car link ${if (connected) "connected" else "disconnected"}")

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                container.parkingDetectionRuntime.handleCarLink(event)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * @return null when the class cannot be read, which [CarLinkPolicy] treats as "not a
     *   car". Refusing to guess is the point: a link wrongly believed to be a car opens a
     *   driving session on a pair of headphones.
     */
    private fun deviceClassOf(device: BluetoothDevice): Int? = try {
        device.bluetoothClass?.deviceClass
    } catch (error: SecurityException) {
        Log.i(TAG, "car link ignored: bluetooth permission not granted")
        null
    }

    private companion object {
        const val TAG = "PkDetection"
    }
}
