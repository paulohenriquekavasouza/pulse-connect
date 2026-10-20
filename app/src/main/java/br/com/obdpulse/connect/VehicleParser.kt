package br.com.obdpulse.connect

import org.json.JSONArray
import org.json.JSONObject

data class TirePressure(
    val pressure: Double?,
    val unit: String,
    val warning: Boolean?,
)

data class VehicleSummary(
    val vin: String?,
    val nickname: String?,
    val make: String?,
    val model: String?,
    val year: String?,
    val odometer: Double?,
    val odometerUnit: String,
    val fuelLevelPercent: Double?,
    val fuelLiters: Double?,
    val fuelLitersUnit: String,
    val distanceToEmpty: Double?,
    val distanceUnit: String,
    val batteryVoltage: Double?,
    val tires: Map<String, TirePressure>,
    val doors: Map<String, String>,
    val windows: Map<String, String>,
    val latitude: Double?,
    val longitude: Double?,
    val approximate: Boolean?,
    val locationTimeMs: Long?,
    val infoTimeMs: Long?,
    val statusTimeMs: Long?,
)

enum class DoorPosition(val jsonKey: String, val label: String, val aliases: Set<String>) {
    DRIVER("driver", "motorista", setOf("driver", "frontleft")),
    PASSENGER("passenger", "passageiro", setOf("passenger", "frontright")),
    REAR_LEFT("rear_left", "traseira esquerda", setOf("leftrear", "rearleft")),
    REAR_RIGHT("rear_right", "traseira direita", setOf("rightrear", "rearright")),
}

object VehicleParser {

    private fun normalize(key: String): String = key.lowercase().replace("_", "").replace("-", "")

    fun statusAt(items: Map<String, String>, position: DoorPosition): String? =
        items.entries.firstOrNull { normalize(it.key) in position.aliases }?.value

    fun unmatched(items: Map<String, String>): Map<String, String> {
        val known = DoorPosition.entries.flatMap { it.aliases }.toSet()
        return items.filterKeys { normalize(it) !in known }
    }

    fun parse(data: VehicleData): VehicleSummary {
        val entry = data.vehicle
        val info = data.status?.optJSONObject("vehicleInfo")
        val odometer = quantity(info, "odometer", "odometer")
        val distance = quantity(info, "fuel", "distanceToEmpty")
        val liters = quantity(info, "fuel", "fuelAmount")
        val location = data.location
        return VehicleSummary(
            vin = text(entry, "vin"),
            nickname = text(entry, "nickname"),
            make = text(entry, "make"),
            model = text(entry, "modelDescription"),
            year = text(entry, "tsoModelYear"),
            odometer = odometer.first,
            odometerUnit = odometer.second ?: "km",
            fuelLevelPercent = quantity(info, "fuel", "fuelAmountLevel").first,
            fuelLiters = liters.first,
            fuelLitersUnit = liters.second?.let { if (it.equals("l", ignoreCase = true)) "L" else it } ?: "L",
            distanceToEmpty = distance.first,
            distanceUnit = distance.second ?: "km",
            batteryVoltage = quantity(info, "batteryInfo", "batteryVoltage").first,
            tires = tires(info),
            doors = statusMap(data.remoteStatus?.opt("doors")),
            windows = statusMap(data.remoteStatus?.opt("windows")),
            latitude = number(location?.opt("latitude")),
            longitude = number(location?.opt("longitude")),
            approximate = location?.opt("isLocationApprox") as? Boolean,
            locationTimeMs = number(location?.opt("timeStamp"))?.toLong(),
            infoTimeMs = number(data.status?.opt("timestamp"))?.toLong(),
            statusTimeMs = number(data.remoteStatus?.opt("timestamp"))?.toLong(),
        )
    }

    private fun text(obj: JSONObject?, key: String): String? {
        val value = obj?.opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return value.toString().takeIf { it.isNotBlank() }
    }

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun quantity(root: JSONObject?, vararg path: String): Pair<Double?, String?> {
        var current: Any? = root
        for (key in path) {
            val next = (current as? JSONObject)?.opt(key)
            if (next == null || next === JSONObject.NULL) return null to null
            current = next
        }
        return when (current) {
            is JSONObject -> number(current.opt("value")) to text(current, "unit")
            else -> number(current) to null
        }
    }

    private fun tires(info: JSONObject?): Map<String, TirePressure> {
        val array = info?.optJSONArray("tyrePressure") ?: return emptyMap()
        val result = LinkedHashMap<String, TirePressure>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = text(item, "type")?.uppercase() ?: continue
            val (value, unit) = quantity(item, "pressure")
            result[type] = TirePressure(value, unit ?: text(item, "unit") ?: "kPa", item.opt("warning") as? Boolean)
        }
        return result
    }

    private fun statusMap(node: Any?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        when (node) {
            is JSONObject -> for (key in node.keys()) result[key] = statusOf(node.opt(key))
            is JSONArray -> for (i in 0 until node.length()) {
                val item = node.opt(i)
                val name = (item as? JSONObject)?.let { text(it, "position") ?: text(it, "type") ?: text(it, "name") }
                    ?: "#${i + 1}"
                result[name] = statusOf(item)
            }
        }
        return result
    }

    private fun statusOf(value: Any?): String = when (value) {
        is JSONObject -> text(value, "status") ?: "?"
        null -> "?"
        else -> value.toString()
    }
}
