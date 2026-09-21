package com.parkingpin.app.domain.detection

/**
 * Decides whether a Bluetooth device that just connected or disconnected is a car
 * (docs/05_PARKING_DETECTION_ENGINE.md §3a "Platform reality").
 *
 * §3a names exactly two device classes — `AUDIO_VIDEO_CAR_AUDIO` and
 * `AUDIO_VIDEO_HANDSFREE` — and this is the whole rule. Headphones, speakers, watches and
 * keyboards fall through, which is the point: a phone connecting to earbuds is not a phone
 * getting into a car, and treating it as one would open a driving session on the walk to
 * the bus stop.
 *
 * ### Why the constants are restated rather than imported
 * The rest of the domain layer holds no SDK types
 * (docs/04_ANDROID_IMPLEMENTATION.md §7), and this is what lets the rule be unit tested on
 * the JVM, where `unitTests.isReturnDefaultValues = true` makes every framework call
 * return zero. The values are fixed by the Bluetooth assigned-numbers specification, not
 * by the Android SDK, so restating them cannot drift. [android.bluetooth.BluetoothClass]
 * is read at the adapter boundary in
 * [com.parkingpin.app.detection.CarLinkReceiver] and nowhere else.
 *
 * ### What it deliberately cannot see
 * A device class integer and nothing more. No name, no MAC address, no alias reaches this
 * function or anything downstream of it, because docs/09 keeps them on the device and
 * docs/17 §3 has no field that could carry one. Structurally, not by convention.
 */
object CarLinkPolicy {

    /** `BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE`. */
    const val DEVICE_CLASS_AUDIO_VIDEO_HANDSFREE: Int = 0x0408

    /** `BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO`. */
    const val DEVICE_CLASS_AUDIO_VIDEO_CAR_AUDIO: Int = 0x0420

    /**
     * @param deviceClass `BluetoothClass.getDeviceClass()`, or null when it could not be
     *   read — which is the ordinary shape of a denied `BLUETOOTH_CONNECT` on API 31+.
     *   Null is not an error and not a car: the link signal is optional (§3a), so its
     *   absence costs accuracy and nothing else.
     */
    fun isCarAudioDevice(deviceClass: Int?): Boolean = when (deviceClass) {
        DEVICE_CLASS_AUDIO_VIDEO_HANDSFREE, DEVICE_CLASS_AUDIO_VIDEO_CAR_AUDIO -> true
        else -> false
    }
}
