package br.com.obdpulse.connect

import android.app.Activity
import android.content.Intent
import android.widget.Toast

object AuthFlow {

    fun relogin(activity: Activity, pending: PendingAction?, notify: Boolean = false) {
        if (activity.isFinishing) return
        SessionHolder.session = null
        SessionHolder.pending = pending
        SessionHolder.returnTo = activity.javaClass
        if (notify) Toast.makeText(activity, R.string.uconnect_session_expired, Toast.LENGTH_SHORT).show()
        activity.startActivity(Intent(activity, LoginActivity::class.java))
        activity.finish()
    }

    fun handle(activity: Activity, error: Exception, pending: PendingAction?): Boolean {
        if (!SessionHolder.shouldRelogin(error)) return false
        relogin(activity, pending, notify = true)
        return true
    }
}
