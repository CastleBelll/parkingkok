package com.parkingkok.app.detection

/** Records what reconciliation asked Play services to do. */
class FakeTransitionRegistrar(
    var permissionGranted: Boolean = true,
    var registerFailure: String? = null,
) : TransitionRegistrar {

    var registerCalls: Int = 0
        private set

    var unregisterCalls: Int = 0
        private set

    override fun hasPermission(): Boolean = permissionGranted

    override suspend fun register(): String? {
        registerCalls++
        return registerFailure
    }

    override suspend fun unregister(): String? {
        unregisterCalls++
        return null
    }
}
