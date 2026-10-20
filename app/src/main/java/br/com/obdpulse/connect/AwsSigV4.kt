package br.com.obdpulse.connect

import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AwsSigV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    fun headers(
        method: String,
        url: URL,
        service: String,
        region: String,
        accessKey: String,
        secretKey: String,
        sessionToken: String,
        body: ByteArray,
        now: Date = Date(),
    ): Map<String, String> {
        val amzDate = format("yyyyMMdd'T'HHmmss'Z'", now)
        val dateStamp = format("yyyyMMdd", now)

        val signed = sortedMapOf<String, String>()
        signed["host"] = url.host
        signed["x-amz-date"] = amzDate
        if (sessionToken.isNotEmpty()) signed["x-amz-security-token"] = sessionToken

        val signedHeaderNames = signed.keys.joinToString(";")
        val canonicalHeaders = signed.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val payloadHash = hex(sha256(body))

        val canonicalRequest = listOf(
            method,
            canonicalUri(url.path),
            canonicalQuery(url.query),
            canonicalHeaders,
            signedHeaderNames,
            payloadHash,
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            hex(sha256(canonicalRequest.toByteArray(Charsets.UTF_8))),
        ).joinToString("\n")

        val signingKey = signatureKey(secretKey, dateStamp, region, service)
        val signature = hex(hmac(signingKey, stringToSign.toByteArray(Charsets.UTF_8)))

        val authorization = "$ALGORITHM Credential=$accessKey/$scope, " +
            "SignedHeaders=$signedHeaderNames, Signature=$signature"

        val out = LinkedHashMap<String, String>()
        out["Authorization"] = authorization
        out["x-amz-date"] = amzDate
        if (sessionToken.isNotEmpty()) out["x-amz-security-token"] = sessionToken
        return out
    }

    private fun canonicalUri(path: String): String {
        val p = path.ifEmpty { "/" }
        return p.split("/").joinToString("/") { encode(it, false) }
    }

    private fun canonicalQuery(query: String?): String {
        if (query.isNullOrEmpty()) return ""
        return query.split("&")
            .map {
                val i = it.indexOf('=')
                if (i < 0) encode(it, true) to "" else encode(it.substring(0, i), true) to encode(it.substring(i + 1), true)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun encode(value: String, encodeSlash: Boolean): String {
        val builder = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '_'.code || c == '-'.code || c == '~'.code || c == '.'.code -> builder.append(c.toChar())
                c == '/'.code && !encodeSlash -> builder.append('/')
                else -> builder.append('%').append("%02X".format(c))
            }
        }
        return builder.toString()
    }

    private fun signatureKey(secret: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmac("AWS4$secret".toByteArray(Charsets.UTF_8), dateStamp.toByteArray(Charsets.UTF_8))
        val kRegion = hmac(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = hmac(kRegion, service.toByteArray(Charsets.UTF_8))
        return hmac(kService, "aws4_request".toByteArray(Charsets.UTF_8))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun format(pattern: String, date: Date): String {
        val formatter = SimpleDateFormat(pattern, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(date)
    }
}
