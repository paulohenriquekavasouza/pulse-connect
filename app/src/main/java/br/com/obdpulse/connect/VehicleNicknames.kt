package br.com.obdpulse.connect

import android.content.Context

object VehicleNicknames {

    const val MAX_LENGTH = 30

    private const val FILE = "nicknames"

    fun get(context: Context, vin: String): String? =
        prefs(context).getString(vin, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, vin: String, nickname: String?) {
        val value = VehicleCatalog.normalizeNickname(nickname)
        prefs(context).edit().apply { if (value == null) remove(vin) else putString(vin, value) }.apply()
        VehicleWidgetProvider.refresh(context)
    }

    fun apply(context: Context, vehicle: UconnectVehicle): UconnectVehicle =
        VehicleCatalog.withNickname(vehicle, get(context, vehicle.vin))

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
