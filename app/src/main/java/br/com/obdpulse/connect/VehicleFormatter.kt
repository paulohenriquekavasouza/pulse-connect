package br.com.obdpulse.connect

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

data class VehicleReport(
    val vehicle: String,
    val panel: String,
    val tires: String,
    val doors: String,
    val location: String,
)

object VehicleFormatter {

    const val EMPTY = "—"
    const val STALE_LOCATION_MS = 10 * 60_000L

    private val BR: Locale = Locale.forLanguageTag("pt-BR")

    private val TIRE_ORDER = listOf(
        "FL" to "Dianteiro esquerdo",
        "FR" to "Dianteiro direito",
        "RL" to "Traseiro esquerdo",
        "RR" to "Traseiro direito",
    )

    fun report(summary: VehicleSummary, zone: TimeZone = TimeZone.getDefault()): VehicleReport {
        val title = listOfNotNull(summary.make, summary.model, summary.year).joinToString(" ").ifBlank { EMPTY }
        val vehicle = buildList {
            add(title)
            summary.nickname?.let { add("Apelido: $it") }
            add("VIN: ${summary.vin ?: EMPTY}")
        }.joinToString("\n")

        val panel = listOf(
            "Hodômetro: ${number(summary.odometer, summary.odometerUnit)}",
            "Combustível: ${fuel(summary)}",
            "Autonomia: ${number(summary.distanceToEmpty, summary.distanceUnit)}",
            "Bateria: ${number(summary.batteryVoltage, "V")}",
        ).joinToString("\n")

        return VehicleReport(
            vehicle = vehicle,
            panel = panel,
            tires = tires(summary.tires),
            doors = doors(summary),
            location = location(summary, zone),
        )
    }

    fun number(value: Double?, unit: String? = null): String {
        if (value == null) return EMPTY
        val body = if (value == floor(value)) String.format(BR, "%,.0f", value) else String.format(BR, "%,.1f", value)
        return if (unit.isNullOrBlank()) body else "$body $unit"
    }

    fun time(ms: Long?, zone: TimeZone = TimeZone.getDefault()): String {
        if (ms == null) return EMPTY
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm", BR)
        format.timeZone = zone
        return format.format(Date(millis(ms)))
    }

    fun relative(ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null) return EMPTY
        val minutes = (now - millis(ms)).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "agora mesmo"
            minutes < 60 -> "há $minutes min"
            minutes < 60 * 24 -> "há ${minutes / 60} h"
            minutes < 60 * 48 -> "há 1 dia"
            else -> "há ${minutes / (60 * 24)} dias"
        }
    }

    fun updated(ms: Long?, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        if (ms == null) return EMPTY
        return "${time(ms, zone)} · ${relative(ms, now)}"
    }

    fun stamp(ms: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("yyyyMMdd", BR).apply { timeZone = zone }
        val sameDay = day.format(Date(millis(ms))) == day.format(Date(now))
        val format = SimpleDateFormat(if (sameDay) "HH:mm" else "dd/MM HH:mm", BR)
        format.timeZone = zone
        return format.format(Date(millis(ms)))
    }

    fun isStale(ms: Long?, now: Long = System.currentTimeMillis(), maxAgeMs: Long = STALE_LOCATION_MS): Boolean =
        ms == null || now - millis(ms) > maxAgeMs

    private fun millis(ms: Long): Long = if (ms < 100_000_000_000L) ms * 1000 else ms

    private fun tires(tires: Map<String, TirePressure>): String {
        val known = TIRE_ORDER.map { (key, label) -> label to tires[key] }
        val extra = tires.filterKeys { key -> TIRE_ORDER.none { it.first == key } }.map { (key, value) -> key to value }
        return (known + extra).joinToString("\n") { (label, tire) ->
            val value = if (tire == null) EMPTY else number(tire.pressure, tire.unit)
            val warning = if (tire?.warning == true) " ⚠" else ""
            "$label: $value$warning"
        }
    }

    private fun fuel(summary: VehicleSummary): String {
        val level = summary.fuelLevelPercent?.let { number(it) + "%" }
        val liters = summary.fuelLiters?.let { number(it, summary.fuelLitersUnit) }
        return when {
            level != null && liters != null -> "$level ($liters)"
            level != null -> level
            liters != null -> liters
            else -> EMPTY
        }
    }

    private fun doors(summary: VehicleSummary): String {
        val lines = mutableListOf<String>()
        for (position in DoorPosition.entries) {
            lines += "Portas · ${position.label}: ${doorStatus(VehicleParser.statusAt(summary.doors, position))}"
        }
        for ((key, status) in VehicleParser.unmatched(summary.doors)) {
            lines += "Portas · $key: ${doorStatus(status)}"
        }
        for (position in listOf(DoorPosition.DRIVER, DoorPosition.PASSENGER)) {
            lines += "Janelas · ${position.label}: ${doorStatus(VehicleParser.statusAt(summary.windows, position))}"
        }
        for ((key, status) in VehicleParser.unmatched(summary.windows)) {
            lines += "Janelas · $key: ${doorStatus(status)}"
        }
        return lines.joinToString("\n")
    }

    private fun doorStatus(status: String?): String = when (status?.uppercase()) {
        null, "?" -> EMPTY
        "LOCKED" -> "travada"
        "UNLOCKED" -> "destravada"
        "CLOSED" -> "fechada"
        "OPEN", "OPENED" -> "aberta"
        else -> status.orEmpty().lowercase()
    }

    private fun location(summary: VehicleSummary, zone: TimeZone): String {
        val position = if (summary.latitude == null || summary.longitude == null) {
            EMPTY
        } else {
            val approx = if (summary.approximate == true) " (aproximada)" else ""
            String.format(Locale.US, "%.5f, %.5f", summary.latitude, summary.longitude) + approx
        }
        return listOf(
            "Posição: $position",
            "Localização atualizada em: ${time(summary.locationTimeMs, zone)}",
            "Dados do veículo em: ${time(summary.infoTimeMs, zone)}",
            "Status remoto em: ${time(summary.statusTimeMs, zone)}",
        ).joinToString("\n")
    }
}

object VehicleJson {

    fun build(data: VehicleData): String {
        val root = JSONObject()
        root.put("summary", summary(VehicleParser.parse(data)))
        root.put("vehicle", data.vehicle ?: JSONObject.NULL)
        root.put("status", data.status ?: JSONObject.NULL)
        root.put("remote_status", data.remoteStatus ?: JSONObject.NULL)
        root.put("location", data.location ?: JSONObject.NULL)
        if (data.extras.isNotEmpty()) {
            val extras = JSONObject()
            for ((key, value) in data.extras) extras.put(key, value)
            root.put("extras", extras)
        }
        root.put("errors", JSONObject(data.errors))
        return root.toString(2).replace("\\/", "/")
    }

    private fun lockedOf(status: String?): Any = when (status?.uppercase()) {
        "LOCKED" -> true
        "UNLOCKED" -> false
        else -> JSONObject.NULL
    }

    private fun closedOf(status: String?): Any = when (status?.uppercase()) {
        "CLOSED" -> true
        "OPEN", "OPENED" -> false
        else -> JSONObject.NULL
    }

    private fun summary(s: VehicleSummary): JSONObject {
        val json = JSONObject()
        json.put("vin", s.vin ?: JSONObject.NULL)
        json.put("nickname", s.nickname ?: JSONObject.NULL)
        json.put("make", s.make ?: JSONObject.NULL)
        json.put("model", s.model ?: JSONObject.NULL)
        json.put("year", s.year ?: JSONObject.NULL)
        json.put("odometer", s.odometer ?: JSONObject.NULL)
        json.put("odometer_unit", s.odometerUnit)
        json.put("fuel_level_percent", s.fuelLevelPercent ?: JSONObject.NULL)
        json.put("fuel_liters", s.fuelLiters ?: JSONObject.NULL)
        json.put("fuel_liters_unit", s.fuelLitersUnit)
        json.put("distance_to_empty", s.distanceToEmpty ?: JSONObject.NULL)
        json.put("distance_to_empty_unit", s.distanceUnit)
        json.put("battery_voltage", s.batteryVoltage ?: JSONObject.NULL)
        val tires = JSONObject()
        for ((type, tire) in s.tires) {
            tires.put(
                type,
                JSONObject()
                    .put("pressure", tire.pressure ?: JSONObject.NULL)
                    .put("unit", tire.unit)
                    .put("warning", tire.warning ?: JSONObject.NULL),
            )
        }
        json.put("tires", tires)
        for (position in DoorPosition.entries) {
            json.put("door_${position.jsonKey}_locked", lockedOf(VehicleParser.statusAt(s.doors, position)))
        }
        json.put("window_driver_closed", closedOf(VehicleParser.statusAt(s.windows, DoorPosition.DRIVER)))
        json.put("window_passenger_closed", closedOf(VehicleParser.statusAt(s.windows, DoorPosition.PASSENGER)))
        json.put(
            "location",
            JSONObject()
                .put("latitude", s.latitude ?: JSONObject.NULL)
                .put("longitude", s.longitude ?: JSONObject.NULL)
                .put("is_approximate", s.approximate ?: JSONObject.NULL)
                .put("updated_ms", s.locationTimeMs ?: JSONObject.NULL),
        )
        json.put("timestamp_info_ms", s.infoTimeMs ?: JSONObject.NULL)
        json.put("timestamp_status_ms", s.statusTimeMs ?: JSONObject.NULL)
        return json
    }
}
