package br.com.obdpulse.connect

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object VehicleCatalog {

    fun parse(list: JSONObject): List<UconnectVehicle> {
        val array = list.optJSONArray("vehicles") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val vin = entry.optString("vin").trim()
            if (vin.isEmpty()) return@mapNotNull null
            UconnectVehicle(
                vin = vin,
                name = name(entry),
                subtitle = subtitle(entry),
                commands = commands(entry),
                imageUrl = VehicleImage.url(entry),
            )
        }
    }

    fun encode(vehicles: List<UconnectVehicle>): String {
        val array = JSONArray()
        for (vehicle in vehicles) {
            array.put(
                JSONObject()
                    .put("vin", vehicle.vin)
                    .put("name", vehicle.name)
                    .put("subtitle", vehicle.subtitle)
                    .put("commands", JSONArray(vehicle.commands.toList()))
                    .put("imageUrl", vehicle.imageUrl ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    fun decode(text: String?): List<UconnectVehicle> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val vin = item.optString("vin").ifBlank { return@mapNotNull null }
                val commands = item.optJSONArray("commands")
                UconnectVehicle(
                    vin = vin,
                    name = item.optString("name").ifBlank { vin },
                    subtitle = item.optString("subtitle"),
                    commands = if (commands == null) emptySet() else (0 until commands.length()).map { commands.optString(it) }.toSet(),
                    imageUrl = item.optString("imageUrl").takeIf { item.opt("imageUrl") is String && it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun normalizeNickname(nickname: String?): String? =
        nickname?.trim()?.replace(Regex("\\s+"), " ")?.take(VehicleNicknames.MAX_LENGTH)?.takeIf { it.isNotEmpty() }

    fun withNickname(vehicle: UconnectVehicle, nickname: String?): UconnectVehicle {
        val name = normalizeNickname(nickname) ?: return vehicle
        if (name == vehicle.name) return vehicle
        val subtitle = listOf(vehicle.name, vehicle.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
        return vehicle.copy(name = name, subtitle = subtitle)
    }

    fun commands(entry: JSONObject): Set<String> {
        val result = LinkedHashSet<String>()
        entry.optJSONArray("supportedCommands")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).trim().uppercase().takeIf { it.isNotEmpty() }?.let(result::add)
            }
        }
        entry.optJSONArray("services")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("service").ifBlank { item.optString("name") }.trim().uppercase()
                if (name.isEmpty()) continue
                if (item.optBoolean("vehicleCapable", true) && item.optBoolean("serviceEnabled", true)) result += name
            }
        }
        return result
    }

    fun name(entry: JSONObject): String {
        val nickname = text(entry, "nickname")
        if (nickname != null) return nickname
        val model = listOfNotNull(text(entry, "make"), text(entry, "modelDescription") ?: text(entry, "model"))
            .joinToString(" ")
        return titleCase(model).ifBlank { text(entry, "vin").orEmpty() }
    }

    fun subtitle(entry: JSONObject): String {
        val year = text(entry, "tsoModelYear") ?: text(entry, "year")
        val model = if (text(entry, "nickname") != null) {
            titleCase(listOfNotNull(text(entry, "make"), text(entry, "modelDescription")).joinToString(" "))
        } else {
            ""
        }
        return listOf(model, year.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun text(obj: JSONObject, key: String): String? {
        val value = obj.opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return value.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun titleCase(value: String): String {
        if (value != value.uppercase()) return value
        return value.lowercase(Locale.ROOT).split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}

object VehicleRouter {

    fun widgetVehicle(vehicles: List<UconnectVehicle>, favoriteVin: String?, lastVin: String?): UconnectVehicle? =
        vehicles.firstOrNull { it.vin == favoriteVin }
            ?: vehicles.firstOrNull { it.vin == lastVin }
            ?: vehicles.firstOrNull()

    fun initialVin(vehicles: List<UconnectVehicle>, favoriteVin: String?): String? =
        vehicles.firstOrNull { favoriteVin != null && it.vin == favoriteVin }?.vin
            ?: vehicles.singleOrNull()?.vin
}
