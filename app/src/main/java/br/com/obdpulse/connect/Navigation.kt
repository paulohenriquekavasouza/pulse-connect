package br.com.obdpulse.connect

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent

object Navigation {

    fun openHome(activity: Activity) {
        val vin = VehicleRouter.initialVin(SessionHolder.vehicles, UconnectStore.favorite(activity))
        activity.startActivity(
            if (vin != null) VehicleActivity.intent(activity, vin, fromList = false)
            else Intent(activity, GarageActivity::class.java),
        )
    }

    fun openGarage(activity: Activity) {
        activity.startActivity(
            Intent(activity, GarageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    fun confirmLogout(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.confirm_logout_title)
            .setMessage(R.string.confirm_logout_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm_logout_ok) { _, _ -> logout(activity) }
            .show()
    }

    private fun logout(activity: Activity) {
        UconnectStore.clear(activity)
        SessionHolder.clear()
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finish()
    }
}
