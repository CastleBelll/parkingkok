package com.parkingpin.app.detection

import com.parkingpin.app.domain.location.LocationSessionConfig

/** Records what the session controller asked Play services to do. */
class FakeLocationSessionRegistrar(
    var foregroundGranted: Boolean = true,
    var backgroundGranted: Boolean = true,
    var requestFailure: String? = null,
) : LocationSessionRegistrar {

    val requestedConfigs = mutableListOf<LocationSessionConfig>()

    var removeCalls: Int = 0
        private set

    /** True while Play services would be holding a request on our behalf. */
    var isRegistered: Boolean = false
        private set

    override fun hasForegroundLocationPermission(): Boolean = foregroundGranted

    override fun hasBackgroundLocationPermission(): Boolean = backgroundGranted

    override suspend fun request(config: LocationSessionConfig): String? {
        requestedConfigs += config
        val failure = requestFailure
        if (failure == null) isRegistered = true
        return failure
    }

    override suspend fun remove(): String? {
        removeCalls++
        isRegistered = false
        return null
    }
}
