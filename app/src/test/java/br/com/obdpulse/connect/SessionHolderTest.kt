package br.com.obdpulse.connect

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHolderTest {

    private val session = UconnectSession("uid", AwsCreds("a", "b", "c"))

    @After
    fun reset() {
        SessionHolder.clear()
    }

    @Test
    fun reloginsOnlyAfterTheGuardWindow() {
        SessionHolder.onLogin(session, "1234", now = 1_000L)
        val auth = UconnectAuthException("expirada")
        assertFalse(SessionHolder.shouldRelogin(auth, now = 1_000L + 5_000L))
        assertTrue(SessionHolder.shouldRelogin(auth, now = 1_000L + SessionHolder.RELOGIN_GUARD_MS + 1))
    }

    @Test
    fun otherErrorsNeverTriggerRelogin() {
        SessionHolder.onLogin(session, null, now = 0L)
        assertFalse(SessionHolder.shouldRelogin(UconnectException("falhou"), now = 10_000_000L))
        assertFalse(SessionHolder.shouldRelogin(IllegalStateException("x"), now = 10_000_000L))
    }

    @Test
    fun clearDropsSessionAndPendingAction() {
        SessionHolder.onLogin(session, "1234", now = 0L)
        SessionHolder.pending = PendingAction(PendingAction.Type.COMMAND, "VIN", "RDL")
        SessionHolder.clear()
        assertNull(SessionHolder.session)
        assertNull(SessionHolder.pin)
        assertNull(SessionHolder.pending)
    }

    @Test
    fun keepsPendingActionUntilConsumed() {
        val action = PendingAction(PendingAction.Type.COMMAND, "VIN", "RDL")
        SessionHolder.pending = action
        SessionHolder.onLogin(session, "1234", now = 0L)
        assertTrue(SessionHolder.pending === action)
    }
}
