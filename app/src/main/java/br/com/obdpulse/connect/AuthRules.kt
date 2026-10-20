package br.com.obdpulse.connect

object AuthRules {

    fun looksExpired(body: String): Boolean {
        val text = body.lowercase()
        return "expired" in text || "security token" in text || "signature" in text
    }

    fun isAuthFailure(code: Int, body: String, expiredOnly: Boolean): Boolean {
        if (code != 401 && code != 403) return false
        if (!expiredOnly) return true
        return code == 401 || looksExpired(body)
    }
}
