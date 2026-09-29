package com.sjstudioz.parkingpin.detection

/** Records what reconciliation asked Play services to do. */
class FakeTransitionRegistrar(
    var permissionGranted: Boolean = true,
    var registerFailure: String? = null,
) : TransitionRegistrar {

    var registerCalls: Int = 0
        private set

    var unregisterCalls: Int = 0
        private set

    /** Suspends inside unregister, so a test can interleave another caller at that point. */
    var yieldOnUnregister: Boolean = false

    override fun hasPermission(): Boolean = permissionGranted

    override suspend fun register(): String? {
        registerCalls++
        return registerFailure
    }

    override suspend fun unregister(): String? {
        unregisterCalls++
        if (yieldOnUnregister) kotlinx.coroutines.yield()
        return null
    }
}
