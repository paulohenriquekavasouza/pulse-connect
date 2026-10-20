package br.com.obdpulse.connect

object SessionHolder {

    const val RELOGIN_GUARD_MS = 20_000L

    @Volatile var session: UconnectSession? = null
    @Volatile var pin: String? = null
    @Volatile var vehicles: List<UconnectVehicle> = emptyList()
    @Volatile var unlocked = false
    @Volatile var pending: PendingAction? = null
    @Volatile var returnTo: Class<*>? = null
    @Volatile private var loggedInAtMs = 0L

    fun onLogin(newSession: UconnectSession, newPin: String?, now: Long = System.currentTimeMillis()) {
        session = newSession
        pin = newPin
        loggedInAtMs = now
    }

    fun shouldRelogin(error: Exception, now: Long = System.currentTimeMillis()): Boolean =
        error is UconnectAuthException && now - loggedInAtMs > RELOGIN_GUARD_MS

    fun clear() {
        session = null
        pin = null
        vehicles = emptyList()
        unlocked = false
        pending = null
        returnTo = null
        loggedInAtMs = 0L
    }
}
