package com.sjstudioz.parkingpin.detection

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.sjstudioz.parkingpin.domain.detection.CarLinkPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Whether a car's audio is connected right now — the re-assertion docs/05 §3a "The latch must
 * not outlive the link" puts on the adapter, which on Android nothing did (audit 2026-10-01).
 * A reboot or a force-stop while connected left the engine believing in a link that was gone,
 * and the latch suppressed `movementIdleWindow` from then on.
 *
 * `null` is "cannot tell" — no Bluetooth permission, Bluetooth off, or the system did not
 * answer — and the caller must then change nothing: a false disconnect mid-drive would raise a
 * candidate at a red light.
 */
class CarLinkProbe(private val context: Context) {

    suspend fun isCarConnected(): Boolean? {
        if (!hasPermission()) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return false
        val answers = PROFILES.map { profile -> connectedCarOn(adapter, profile) }
        if (answers.any { it == true }) return true
        return if (answers.all { it == false }) false else null
    }

    private suspend fun connectedCarOn(adapter: android.bluetooth.BluetoothAdapter, profile: Int): Boolean? =
        withTimeoutOrNull(PROXY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                        val car = try {
                            proxy.connectedDevices.any { CarLinkPolicy.isCarAudioDevice(it.bluetoothClass?.deviceClass) }
                        } catch (denied: SecurityException) {
                            Log.i(TAG, "car link probe: ${denied.javaClass.simpleName}")
                            null
                        } finally {
                            adapter.closeProfileProxy(profileId, proxy)
                        }
                        if (continuation.isActive) continuation.resume(car)
                    }

                    override fun onServiceDisconnected(profileId: Int) = Unit
                }
                if (!adapter.getProfileProxy(context, listener, profile) && continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "PkDetection"
        const val PROXY_TIMEOUT_MILLIS = 3_000L
        /** Car audio arrives as hands-free, media, or both. */
        val PROFILES = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
    }
}
