package br.com.obdpulse.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCatalogTest {

    private fun vehicle(vin: String, commands: Set<String> = emptySet()) =
        UconnectVehicle(vin, vin, "", commands, null)

    @Test
    fun readsCommandsFromSupportedCommandsAndServices() {
        val entry = JSONObject(
            """{"vin":"V1","supportedCommands":["rdl"],"services":[
                {"service":"RDU","vehicleCapable":true,"serviceEnabled":true},
                {"service":"REON","vehicleCapable":true,"serviceEnabled":false},
                {"service":"VF","vehicleCapable":false,"serviceEnabled":true},
                {"service":"HBLF"}]}""",
        )
        assertEquals(setOf("RDL", "RDU", "HBLF"), VehicleCatalog.commands(entry))
    }

    @Test
    fun namesVehicleFromNicknameOrModel() {
        val plain = JSONObject("""{"vin":"V1","make":"FIAT","modelDescription":"PULSE ABARTH","tsoModelYear":"2026"}""")
        assertEquals("Fiat Pulse Abarth", VehicleCatalog.name(plain))
        assertEquals("2026", VehicleCatalog.subtitle(plain))

        val named = JSONObject("""{"vin":"V1","nickname":"Meu carro","make":"FIAT","modelDescription":"PULSE","tsoModelYear":"2026"}""")
        assertEquals("Meu carro", VehicleCatalog.name(named))
        assertEquals("Fiat Pulse · 2026", VehicleCatalog.subtitle(named))
    }

    @Test
    fun skipsEntriesWithoutVin() {
        val list = JSONObject("""{"vehicles":[{"vin":""},{"vin":"V2","make":"FIAT"}]}""")
        assertEquals(listOf("V2"), VehicleCatalog.parse(list).map { it.vin })
    }

    @Test
    fun buildsImageUrlFromMmvs() {
        val entry = JSONObject(
            """{"vin":"V1","tsoModelYear":"2026","details":{"mmvs":"00363ASZ1743","options":["007","008","1j8"]}}""",
        )
        val url = VehicleImage.url(entry, 260, 160)!!
        assertTrue(url.startsWith(VehicleImage.BASE_URL + "?source=connectivity&consumer=responsive"))
        assertTrue(url.contains("&market=3433&brand=00&model=363&mmvs=00363ASZ1743&body=743"))
        assertTrue(url.contains("&opt=007,008,1J8&year=2026&view=EXT&angle=1&width=260&height=160&raiseError=true"))
    }

    @Test
    fun findsMmvsInAnyFieldAndPrefersExplicitBody() {
        val entry = JSONObject("""{"vin":"V1","modelCode":"00363ASZ1743","tsoBodyCode":"744","year":2025}""")
        val url = VehicleImage.url(entry)!!
        assertTrue(url.contains("mmvs=00363ASZ1743&body=744"))
        assertTrue(url.contains("year=2025"))
    }

    @Test
    fun reusesImageUrlFromApiWithRequestedSize() {
        val entry = JSONObject(
            """{"vin":"V1","imageUrl":"https://asset-service.k8s.fcalatam.com.br/asset/getPngImage?mmvs=X&width=100&height=50"}""",
        )
        assertEquals(
            "https://asset-service.k8s.fcalatam.com.br/asset/getPngImage?mmvs=X&width=780&height=480",
            VehicleImage.url(entry),
        )
    }

    @Test
    fun derivesMmvsFromVinForKnownSeries() {
        val entry = JSONObject(
            """{"vin":"9BD363ASZTYT00000","model":"363","brandCode":"00","tsoBodyCode":"6V","market":"BR","tsoModelYear":"2026"}""",
        )
        val url = VehicleImage.url(entry, 260, 160)!!
        assertTrue(url.contains("&market=3433&brand=00&model=363&mmvs=00363ASZ1743&body=743&year=2026"))
    }

    @Test
    fun noImageForUnknownSeries() {
        val entry = JSONObject("""{"vin":"9BD999XYZTYT00000","model":"999","brandCode":"00"}""")
        assertNull(VehicleImage.url(entry))
    }

    @Test
    fun noImageWithoutMmvs() {
        assertNull(VehicleImage.url(JSONObject("""{"vin":"V1","make":"FIAT","modelDescription":"PULSE"}""")))
    }

    @Test
    fun routesToFavoriteOrSingleVehicle() {
        val two = listOf(vehicle("A"), vehicle("B"))
        assertEquals("B", VehicleRouter.initialVin(two, "B"))
        assertNull(VehicleRouter.initialVin(two, null))
        assertNull(VehicleRouter.initialVin(two, "Z"))
        assertEquals("A", VehicleRouter.initialVin(listOf(vehicle("A")), null))
        assertNull(VehicleRouter.initialVin(emptyList(), "A"))
    }

    @Test
    fun encodesAndDecodesVehicleSnapshot() {
        val vehicles = listOf(
            UconnectVehicle("V1", "Fiat Pulse", "2026", setOf("RDL", "REON"), "https://x/img?a=1"),
            UconnectVehicle("V2", "Outro", "", emptySet(), null),
        )
        assertEquals(vehicles, VehicleCatalog.decode(VehicleCatalog.encode(vehicles)))
        assertEquals(emptyList<UconnectVehicle>(), VehicleCatalog.decode("lixo"))
        assertEquals(emptyList<UconnectVehicle>(), VehicleCatalog.decode(null))
    }

    @Test
    fun widgetPrefersFavoriteThenLastOpenedThenFirst() {
        val list = listOf(vehicle("A"), vehicle("B"), vehicle("C"))
        assertEquals("C", VehicleRouter.widgetVehicle(list, "C", "B")?.vin)
        assertEquals("B", VehicleRouter.widgetVehicle(list, null, "B")?.vin)
        assertEquals("A", VehicleRouter.widgetVehicle(list, "Z", null)?.vin)
        assertNull(VehicleRouter.widgetVehicle(emptyList(), "A", "A"))
    }

    @Test
    fun stampShowsDateOnlyForPreviousDays() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = 1_000_000_000_000L
        assertEquals("01:46", VehicleFormatter.stamp(now, now, utc))
        assertEquals("01:16", VehicleFormatter.stamp(now - 30 * 60_000, now, utc))
        assertEquals("08/09 23:46", VehicleFormatter.stamp(now - 2 * 3_600_000, now, utc))
        assertEquals("08/09 23:46", VehicleFormatter.stamp((now - 2 * 3_600_000) / 1000, now, utc))
    }

    @Test
    fun locationIsStaleAfterTenMinutes() {
        val now = 1_000_000_000_000L
        assertTrue(VehicleFormatter.isStale(null, now))
        assertFalse(VehicleFormatter.isStale(now - 9 * 60_000, now))
        assertFalse(VehicleFormatter.isStale(now - 10 * 60_000, now))
        assertTrue(VehicleFormatter.isStale(now - 11 * 60_000, now))
        assertTrue(VehicleFormatter.isStale((now - 11 * 60_000) / 1000, now))
    }

    @Test
    fun formatsRelativeUpdateTime() {
        val now = 1_000_000_000_000L
        assertEquals("agora mesmo", VehicleFormatter.relative(now - 30_000, now))
        assertEquals("há 5 min", VehicleFormatter.relative(now - 5 * 60_000, now))
        assertEquals("há 3 h", VehicleFormatter.relative(now - 3 * 3_600_000, now))
        assertEquals("há 1 dia", VehicleFormatter.relative(now - 30 * 3_600_000L, now))
        assertEquals("há 4 dias", VehicleFormatter.relative(now - 4 * 86_400_000L, now))
        assertEquals("há 5 min", VehicleFormatter.relative((now - 5 * 60_000) / 1000, now))
    }
}
