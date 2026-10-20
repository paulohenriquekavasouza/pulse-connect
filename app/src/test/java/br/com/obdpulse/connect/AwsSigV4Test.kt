package br.com.obdpulse.connect

import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Test

class AwsSigV4Test {

    @Test
    fun matchesAwsGetVanillaTestVector() {
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        val date = formatter.parse("20150830T123600Z")!!

        val headers = AwsSigV4.headers(
            method = "GET",
            url = URL("https://example.amazonaws.com/"),
            service = "service",
            region = "us-east-1",
            accessKey = "AKIDEXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            sessionToken = "",
            body = ByteArray(0),
            now = date,
        )

        val auth = headers["Authorization"]!!
        assertTrue(auth.contains("SignedHeaders=host;x-amz-date"))
        assertTrue(auth.contains("Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"))
    }
}
