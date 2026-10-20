package br.com.obdpulse.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {

    @Test
    fun anyForbiddenOrUnauthorizedIsAuthFailureOnMainCalls() {
        assertTrue(AuthRules.isAuthFailure(401, "", expiredOnly = false))
        assertTrue(AuthRules.isAuthFailure(403, "Forbidden", expiredOnly = false))
    }

    @Test
    fun successAndOtherErrorsAreNeverAuthFailures() {
        assertFalse(AuthRules.isAuthFailure(200, "expired", expiredOnly = false))
        assertFalse(AuthRules.isAuthFailure(404, "expired", expiredOnly = false))
        assertFalse(AuthRules.isAuthFailure(500, "", expiredOnly = true))
    }

    @Test
    fun optionalEndpointsOnlyCountAsExpiredWhenTheBodySaysSo() {
        assertFalse(AuthRules.isAuthFailure(403, """{"message":"Forbidden"}""", expiredOnly = true))
        assertFalse(AuthRules.isAuthFailure(403, """{"message":"User is not authorized"}""", expiredOnly = true))
        assertTrue(AuthRules.isAuthFailure(403, """{"message":"The security token included in the request is expired"}""", expiredOnly = true))
        assertTrue(AuthRules.isAuthFailure(403, """{"message":"Signature expired: 2026"}""", expiredOnly = true))
        assertTrue(AuthRules.isAuthFailure(401, "", expiredOnly = true))
    }
}
