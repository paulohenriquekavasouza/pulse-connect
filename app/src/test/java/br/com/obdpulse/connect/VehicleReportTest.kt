package br.com.obdpulse.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class VehicleReportTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun data(
        vehicle: String? = """{"vin":"TESTVIN0000000001","nickname":"","make":"FIAT","modelDescription":"PULSE","tsoModelYear":"2026"}""",
        status: String? = """{"timestamp":1000000000000,"vehicleInfo":{
            "odometer":{"odometer":{"value":7736,"unit":"km"}},
            "fuel":{"fuelAmountLevel":22,"distanceToEmpty":{"value":184,"unit":"km"}},
            "batteryInfo":{"batteryVoltage":{"value":12.2,"unit":"V"}},
            "tyrePressure":[{"type":"FL","pressure":{"value":230,"unit":"kPa"},"warning":false},
                            {"type":"RR","pressure":{"value":180,"unit":"kPa"},"warning":true}]}}""",
        remote: String? = """{"timestamp":1000000000000,
            "doors":{"driver":{"status":"LOCKED"},"passenger":{"status":"UNLOCKED"}},
            "windows":{"driver":{"status":"CLOSED"}}}""",
        location: String? = """{"latitude":-23.55052,"longitude":-46.63331,"altitude":760.0,"bearing":0,
            "isLocationApprox":true,"timeStamp":1000000000000}""",
    ) = VehicleData(
        vehicle = vehicle?.let { JSONObject(it) },
        status = status?.let { JSONObject(it) },
        remoteStatus = remote?.let { JSONObject(it) },
        location = location?.let { JSONObject(it) },
        errors = emptyMap(),
    )

    @Test
    fun parsesPanelValues() {
        val summary = VehicleParser.parse(data())
        assertEquals(7736.0, summary.odometer!!, 0.0)
        assertEquals(22.0, summary.fuelLevelPercent!!, 0.0)
        assertEquals(184.0, summary.distanceToEmpty!!, 0.0)
        assertEquals(12.2, summary.batteryVoltage!!, 0.0)
        assertEquals("PULSE", summary.model)
        assertNull(summary.nickname)
    }

    @Test
    fun formatsReportInPortuguese() {
        val report = VehicleFormatter.report(VehicleParser.parse(data()), utc)

        assertTrue(report.vehicle.contains("FIAT PULSE 2026"))
        assertTrue(report.vehicle.contains("VIN: TESTVIN0000000001"))
        assertFalse(report.vehicle.contains("Apelido"))

        assertTrue(report.panel.contains("Hodômetro: 7.736 km"))
        assertTrue(report.panel.contains("Combustível: 22%"))
        assertTrue(report.panel.contains("Autonomia: 184 km"))
        assertTrue(report.panel.contains("Bateria: 12,2 V"))

        assertTrue(report.tires.contains("Dianteiro esquerdo: 230 kPa"))
        assertTrue(report.tires.contains("Dianteiro direito: —"))
        assertTrue(report.tires.contains("Traseiro direito: 180 kPa ⚠"))

        assertTrue(report.doors.contains("Portas · motorista: travada"))
        assertTrue(report.doors.contains("Portas · passageiro: destravada"))
        assertTrue(report.doors.contains("Janelas · motorista: fechada"))

        assertTrue(report.location.contains("-23.55052, -46.63331 (aproximada)"))
        assertTrue(report.location.contains("Localização atualizada em: 09/09/2001 01:46"))
    }

    @Test
    fun readsDoorsFromArrays() {
        val summary = VehicleParser.parse(
            data(remote = """{"doors":[{"position":"leftRear","status":"LOCKED"},{"status":"UNLOCKED"}]}"""),
        )
        assertEquals("LOCKED", summary.doors["leftRear"])
        assertEquals("UNLOCKED", summary.doors["#2"])
    }

    @Test
    fun missingSectionsShowDashes() {
        val report = VehicleFormatter.report(
            VehicleParser.parse(data(vehicle = null, status = null, remote = null, location = null)),
            utc,
        )
        assertTrue(report.panel.contains("Hodômetro: —"))
        assertTrue(report.tires.contains("Dianteiro esquerdo: —"))
        assertTrue(report.doors.contains("Portas · motorista: —"))
        assertTrue(report.location.contains("Posição: —"))
    }

    @Test
    fun realPulseShapeWithStringValuesAndEmptyRemoteStatus() {
        val status = """{"timestamp":1790864163298,"vehicleInfo":{
            "odometer":{"odometer":{"value":"7736","unit":"km"}},
            "tyrePressure":[
              {"pressure":{"unit":"kPa","value":"null"},"warning":false,"type":"FL","status":"NORMAL"},
              {"status":"NORMAL","warning":false,"pressure":{"value":"null","unit":"kPa"},"type":"FR"}],
            "fuel":{"fuelAmount":{"unit":"l","value":"11.0"},"distanceToEmpty":{"unit":"km","value":"184"},
                    "fuelAmountLevel":22,"isFuelLevelLow":false},
            "batteryInfo":{"batteryStateOfCharge":"null","batteryVoltage":{"unit":"volts","value":"12.2"}}}}"""
        val sample = data(status = status, remote = "{}")

        val summary = VehicleParser.parse(sample)
        assertEquals(7736.0, summary.odometer!!, 0.0)
        assertEquals(184.0, summary.distanceToEmpty!!, 0.0)
        assertEquals(12.2, summary.batteryVoltage!!, 0.0)
        assertEquals(22.0, summary.fuelLevelPercent!!, 0.0)
        assertEquals(11.0, summary.fuelLiters!!, 0.0)
        assertEquals("L", summary.fuelLitersUnit)
        assertNull(summary.tires["FL"]!!.pressure)
        assertTrue(summary.doors.isEmpty())
        assertNull(summary.statusTimeMs)

        val report = VehicleFormatter.report(summary, utc)
        assertTrue(report.panel.contains("Combustível: 22% (11 L)"))
        assertTrue(report.tires.contains("Dianteiro esquerdo: —"))
        assertTrue(report.doors.contains("Portas · motorista: —"))
        assertTrue(report.doors.contains("Portas · traseira direita: —"))
        assertTrue(report.doors.contains("Janelas · passageiro: —"))

        val json = JSONObject(VehicleJson.build(sample)).getJSONObject("summary")
        assertTrue(json.has("door_driver_locked"))
        assertTrue(json.isNull("door_driver_locked"))
        assertTrue(json.has("door_rear_right_locked"))
        assertTrue(json.has("window_passenger_closed"))
        assertTrue(json.isNull("window_driver_closed"))
        assertEquals(22.0, json.getDouble("fuel_level_percent"), 0.0)
        assertEquals(11.0, json.getDouble("fuel_liters"), 0.0)
    }

    @Test
    fun jsonIncludesExtrasOnlyWhenPresent() {
        val base = data()
        assertFalse(JSONObject(VehicleJson.build(base)).has("extras"))

        val withExtras = base.copy(
            extras = linkedMapOf(
                "vhr" to JSONObject("""{"dtc":[]}"""),
                "status_v3" to JSONObject("""{"timestamp":1}"""),
            ),
            errors = mapOf("subscription" to "HTTP 404"),
        )
        val json = JSONObject(VehicleJson.build(withExtras))
        assertTrue(json.getJSONObject("extras").has("vhr"))
        assertTrue(json.getJSONObject("extras").has("status_v3"))
        assertEquals("HTTP 404", json.getJSONObject("errors").getString("subscription"))
    }

    @Test
    fun lockStateIsReportedWhenTheApiProvidesIt() {
        val sample = data(
            remote = """{"doors":{"driver":{"status":"LOCKED"},"leftRear":{"status":"UNLOCKED"}},
                "windows":{"passenger":{"status":"CLOSED"}}}""",
        )
        val json = JSONObject(VehicleJson.build(sample)).getJSONObject("summary")
        assertEquals(true, json.getBoolean("door_driver_locked"))
        assertEquals(false, json.getBoolean("door_rear_left_locked"))
        assertTrue(json.isNull("door_passenger_locked"))
        assertEquals(true, json.getBoolean("window_passenger_closed"))
    }

    @Test
    fun timeAcceptsSecondsAndMilliseconds() {
        assertEquals("09/09/2001 01:46", VehicleFormatter.time(1_000_000_000L, utc))
        assertEquals("09/09/2001 01:46", VehicleFormatter.time(1_000_000_000_000L, utc))
        assertEquals("—", VehicleFormatter.time(null, utc))
    }

    @Test
    fun numberUsesBrazilianFormat() {
        assertEquals("7.736 km", VehicleFormatter.number(7736.0, "km"))
        assertEquals("12,2 V", VehicleFormatter.number(12.2, "V"))
        assertEquals("—", VehicleFormatter.number(null, "km"))
    }

    @Test
    fun jsonContainsSummaryAndRawSections() {
        val text = VehicleJson.build(
            data(vehicle = """{"vin":"TESTVIN0000000001","image":"a/b"}"""),
        )
        val json = JSONObject(text)
        assertTrue(json.has("summary"))
        assertTrue(json.has("vehicle"))
        assertTrue(json.has("status"))
        assertTrue(json.has("remote_status"))
        assertTrue(json.has("location"))
        assertTrue(json.has("errors"))
        assertEquals(7736.0, json.getJSONObject("summary").getDouble("odometer"), 0.0)
        assertTrue(text.contains("a/b"))
        assertFalse(text.contains("a\\/b"))
    }
}
