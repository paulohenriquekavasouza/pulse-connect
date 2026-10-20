package br.com.obdpulse.connect

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object VehicleImage {

    const val BASE_URL = "https://asset-service.k8s.fcalatam.com.br/asset/getPngImage"
    const val MARKET_BR = "3433"
    const val WIDTH = 780
    const val HEIGHT = 480

    private val MMVS = Regex("^\\d{5}[A-Z0-9]{4}\\d{3}$")
    private val KNOWN_SERIES = mapOf(
        "00363ASZ" to "1743",
    )
    private val MMVS_KEYS = listOf("mmvs", "mmvscode", "mmvsid")
    private val BRAND_KEYS = listOf("brandcode", "brand")
    private val BODY_KEYS = listOf("tsobodycode", "bodycode", "body")
    private val YEAR_KEYS = listOf("tsomodelyear", "modelyear", "year")
    private val MARKET_KEYS = listOf("market", "marketcode")
    private val OPTION_KEYS = listOf("opt", "opts", "options", "optionals", "optioncodes", "optionalcodes", "optcodes")

    fun url(entry: JSONObject?, width: Int = WIDTH, height: Int = HEIGHT): String? {
        if (entry == null) return null
        val fields = LinkedHashMap<String, Any>()
        collect(entry, fields)
        fields.values.filterIsInstance<String>()
            .firstOrNull { it.contains("getPngImage") }
            ?.let { return resize(it, width, height) }

        val mmvs = scalar(fields, MMVS_KEYS)?.uppercase()?.takeIf { MMVS.matches(it) }
            ?: fields.values.filterIsInstance<String>().map { it.trim().uppercase() }.firstOrNull { MMVS.matches(it) }
            ?: fromVin(fields)
            ?: return null
        val brand = numeric(fields, BRAND_KEYS)?.takeIf { it.length == 2 } ?: mmvs.substring(0, 2)
        val model = mmvs.substring(2, 5)
        val body = numeric(fields, BODY_KEYS)?.takeIf { it.length == 3 } ?: mmvs.substring(9, 12)
        val year = numeric(fields, YEAR_KEYS)?.takeIf { it.length == 4 }
        val market = numeric(fields, MARKET_KEYS)?.takeIf { it.length == 4 } ?: MARKET_BR
        val options = options(fields)

        val params = buildList {
            add("source" to "connectivity")
            add("consumer" to "responsive")
            add("market" to market)
            add("brand" to brand)
            add("model" to model)
            add("mmvs" to mmvs)
            add("body" to body)
            if (options != null) add("opt" to options)
            if (year != null) add("year" to year)
            add("view" to "EXT")
            add("angle" to "1")
            add("width" to width.toString())
            add("height" to height.toString())
            add("raiseError" to "true")
        }
        return BASE_URL + "?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    private fun fromVin(fields: Map<String, Any>): String? {
        val vin = scalar(fields, listOf("vin"))?.uppercase()?.takeIf { it.length == 17 } ?: return null
        val brand = numeric(fields, BRAND_KEYS)?.takeIf { it.length == 2 } ?: return null
        val model = numeric(fields, listOf("model"))?.takeIf { it.length == 3 } ?: vin.substring(3, 6)
        val prefix = brand + model + vin.substring(6, 9)
        return KNOWN_SERIES[prefix]?.let { prefix + it }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("%2C", ",")

    private fun normalize(key: String): String = key.lowercase().replace("_", "").replace("-", "")

    private fun collect(node: Any?, out: MutableMap<String, Any>) {
        when (node) {
            is JSONObject -> for (key in node.keys()) {
                val value = node.opt(key)
                if (value == null || value === JSONObject.NULL) continue
                val name = normalize(key)
                if (name !in out) out[name] = value
                if (value is JSONObject || value is JSONArray) collect(value, out)
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val item = node.opt(i)
                if (item is JSONObject || item is JSONArray) collect(item, out)
            }
        }
    }

    private fun scalar(fields: Map<String, Any>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            when (val value = fields[key]) {
                is String -> value.trim().takeIf { it.isNotEmpty() }
                is Number -> value.toLong().toString()
                else -> null
            }
        }

    private fun numeric(fields: Map<String, Any>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> scalar(fields, listOf(key))?.takeIf { v -> v.all { it.isDigit() } } }

    private fun options(fields: Map<String, Any>): String? {
        for (key in OPTION_KEYS) {
            val codes = when (val value = fields[key]) {
                is JSONArray -> (0 until value.length()).mapNotNull { i ->
                    when (val item = value.opt(i)) {
                        is String -> item
                        is JSONObject -> item.optString("code").ifBlank { item.optString("id") }
                        else -> null
                    }
                }
                is String -> value.split(',', ';', ' ')
                else -> continue
            }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            if (codes.isNotEmpty()) return codes.joinToString(",")
        }
        return null
    }

    private fun resize(url: String, width: Int, height: Int): String {
        var result = url.trim()
        result = setParam(result, "width", width.toString())
        result = setParam(result, "height", height.toString())
        return result
    }

    private fun setParam(url: String, name: String, value: String): String {
        val pattern = Regex("([?&])$name=[^&]*")
        return if (pattern.containsMatchIn(url)) {
            url.replace(pattern) { "${it.groupValues[1]}$name=$value" }
        } else {
            url + (if (url.contains('?')) "&" else "?") + "$name=$value"
        }
    }
}
