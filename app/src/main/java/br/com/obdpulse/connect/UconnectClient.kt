package br.com.obdpulse.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random

object UconnectClient {

    const val CMD_LOCK = "RDL"
    const val CMD_UNLOCK = "RDU"
    const val CMD_LOCATE = "VF"
    const val CMD_START = "REON"
    const val CMD_STOP = "REOFF"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    suspend fun login(email: String, password: String): UconnectSession = withContext(Dispatchers.IO) {
        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        bootstrap()
        val loginToken = gigyaLogin(email, password)
        val idToken = gigyaJwt(loginToken)
        val uid = subjectOf(idToken)
        val (token, identityId) = tokenExchange(idToken)
        val creds = cognitoCredentials(identityId, token)
        UconnectSession(uid, creds)
    }

    suspend fun listVehicles(session: UconnectSession): List<UconnectVehicle> = withContext(Dispatchers.IO) {
        VehicleCatalog.parse(getJson(session, vehiclesUrl(session), "Listar veículos"))
    }

    suspend fun location(session: UconnectSession, vin: String): VehicleLocation? = withContext(Dispatchers.IO) {
        val json = getJson(session, locationUrl(session, vin), "Obter localização")
        if (!json.has("latitude") || !json.has("longitude")) return@withContext null
        VehicleLocation(
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            updatedMs = json.optLong("timeStamp").takeIf { it > 0 },
        )
    }

    suspend fun fetchVehicleData(
        session: UconnectSession,
        vin: String?,
        explore: Boolean = false,
    ): VehicleData = withContext(Dispatchers.IO) {
        val errors = LinkedHashMap<String, String>()
        val list = guarded(errors, "vehicles") { getJson(session, vehiclesUrl(session), "Listar veículos") }
        val entry = pickVehicle(list, vin)
        val resolvedVin = entry?.optString("vin")?.takeIf { it.isNotBlank() } ?: vin
        if (resolvedVin == null) {
            return@withContext VehicleData(entry, null, null, null, errors)
        }
        val base = UconnectBrand.API_URL
        val uid = session.uid
        val status = guarded(errors, "status") {
            try {
                getJson(session, "$base/v4/accounts/$uid/vehicles/$resolvedVin/status/", "Status v4")
            } catch (e: UconnectAuthException) {
                throw e
            } catch (e: UconnectException) {
                getJson(session, "$base/v3/accounts/$uid/vehicles/$resolvedVin/status/", "Status v3")
            }
        }
        val remote = guarded(errors, "remote_status") {
            getJson(session, "$base/v1/accounts/$uid/vehicles/$resolvedVin/remote/status", "Status remoto")
        }
        val location = guarded(errors, "location") {
            getJson(session, locationUrl(session, resolvedVin), "Localização")
        }
        val extras = LinkedHashMap<String, JSONObject>()
        if (explore) {
            val prefix = "/accounts/$uid/vehicles/$resolvedVin"
            val paths = listOf(
                "status_v4" to "/v4$prefix/status/",
                "status_v3" to "/v3$prefix/status/",
                "vhr" to "/v1$prefix/vhr/",
                "maintenance_history" to "/v1$prefix/maintenance/history/",
                "notifications" to "/v1$prefix/notifications?limit=30",
                "subscription" to "/v1$prefix/subscription/",
                "svla_status" to "/v1$prefix/svla/status/",
            )
            for ((key, path) in paths) {
                guarded(errors, key) { getJson(session, base + path, key, expiredOnly = true) }
                    ?.let { extras[key] = it }
            }
        }
        VehicleData(entry, status, remote, location, errors, extras)
    }

    suspend fun authenticatePin(session: UconnectSession, pin: String): String = withContext(Dispatchers.IO) {
        val url = "${UconnectBrand.AUTH_URL}/v1/accounts/${session.uid}/ignite/pin/authenticate"
        val encoded = Base64.getEncoder().encodeToString(pin.toByteArray(Charsets.UTF_8))
        val body = JSONObject().put("pin", encoded).toString().toByteArray(Charsets.UTF_8)
        val (code, resp) = signed("POST", url, UconnectBrand.AUTH_KEY, body, session)
        if (AuthRules.isAuthFailure(code, resp, expiredOnly = true)) {
            throw UconnectAuthException("PIN: acesso negado ($code) ${snippet(resp)}")
        }
        if (code !in 200..299) throw UconnectException("PIN recusado ($code): ${snippet(resp)}")
        JSONObject(resp).optString("token").ifBlank { throw UconnectException("PIN sem token na resposta.") }
    }

    suspend fun command(session: UconnectSession, vin: String, name: String, pinAuth: String): Unit =
        withContext(Dispatchers.IO) {
            val path = if (name == CMD_LOCATE) "location" else "remote"
            val url = "${UconnectBrand.API_URL}/v1/accounts/${session.uid}/vehicles/$vin/$path"
            val body = JSONObject().put("command", name).put("pinAuth", pinAuth).toString().toByteArray(Charsets.UTF_8)
            val (code, resp) = signed("POST", url, UconnectBrand.API_KEY, body, session)
            ensureOk(code, resp, "Comando $name")
        }

    private fun vehiclesUrl(session: UconnectSession): String =
        "${UconnectBrand.API_URL}/v4/accounts/${session.uid}/vehicles?stage=ALL&sdp=ALL"

    private fun locationUrl(session: UconnectSession, vin: String): String =
        "${UconnectBrand.API_URL}/v1/accounts/${session.uid}/vehicles/$vin/location/lastknown"

    private fun pickVehicle(list: JSONObject?, vin: String?): JSONObject? {
        val array = list?.optJSONArray("vehicles") ?: return null
        var first: JSONObject? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (first == null) first = item
            if (vin != null && item.optString("vin") == vin) return item
        }
        return first
    }

    private fun <T> guarded(errors: MutableMap<String, String>, key: String, block: () -> T): T? =
        try {
            block()
        } catch (e: UconnectAuthException) {
            throw e
        } catch (e: Exception) {
            errors[key] = e.message ?: e.toString()
            null
        }

    private fun getJson(
        session: UconnectSession,
        url: String,
        what: String,
        expiredOnly: Boolean = false,
    ): JSONObject {
        val (code, body) = signed("GET", url, UconnectBrand.API_KEY, ByteArray(0), session)
        ensureOk(code, body, what, expiredOnly)
        return parseObject(body)
    }

    private fun parseObject(body: String): JSONObject {
        val text = body.trim()
        return try {
            if (text.startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
        } catch (e: Exception) {
            JSONObject().put("raw", truncate(text, 400))
        }
    }

    private fun ensureOk(code: Int, body: String, what: String, expiredOnly: Boolean = false) {
        if (AuthRules.isAuthFailure(code, body, expiredOnly)) {
            throw UconnectAuthException("$what: acesso negado ($code) ${snippet(body)}")
        }
        if (code !in 200..299) throw UconnectException("$what falhou ($code): ${snippet(body)}")
    }

    private fun snippet(body: String): String = truncate(body.trim(), 200)

    private fun bootstrap() {
        val url = "${UconnectBrand.LOGIN_URL}/accounts.webSdkBootstrap?apiKey=${enc(UconnectBrand.LOGIN_API_KEY)}"
        execute("GET", url, emptyMap(), null)
    }

    private fun defaultParams(): MutableMap<String, String> = mutableMapOf(
        "targetEnv" to "jssdk",
        "loginMode" to "standard",
        "sdk" to "js_latest",
        "authMode" to "cookie",
        "sdkBuild" to "12234",
        "format" to "json",
        "APIKey" to UconnectBrand.LOGIN_API_KEY,
    )

    private fun gigyaLogin(email: String, password: String): String {
        val params = defaultParams().apply {
            put("loginID", email)
            put("password", password)
            put("sessionExpiration", "300")
            put("include", "profile,data,emails,subscriptions,preferences")
        }
        val (_, body) = execute("POST", "${UconnectBrand.LOGIN_URL}/accounts.login?${query(params)}", emptyMap(), null)
        val json = JSONObject(body)
        if (json.optInt("errorCode", -1) != 0) {
            throw UconnectException("Login falhou: ${gigyaError(json)}")
        }
        return json.optJSONObject("sessionInfo")?.optString("login_token").orEmpty()
            .ifBlank { throw UconnectException("Login sem login_token.") }
    }

    private fun gigyaJwt(loginToken: String): String {
        val params = defaultParams().apply {
            put("login_token", loginToken)
            put("fields", "profile.firstName,profile.lastName,profile.email,country,locale,data.disclaimerCodeGSDP")
        }
        val (_, body) = execute("POST", "${UconnectBrand.LOGIN_URL}/accounts.getJWT?${query(params)}", emptyMap(), null)
        val json = JSONObject(body)
        if (json.optInt("errorCode", -1) != 0) {
            throw UconnectException("getJWT falhou: ${gigyaError(json)}")
        }
        return json.optString("id_token").ifBlank { throw UconnectException("getJWT sem id_token.") }
    }

    private fun tokenExchange(idToken: String): Pair<String, String> {
        val headers = defaultHeaders(UconnectBrand.API_KEY) + ("content-type" to "application/json")
        val body = JSONObject().put("gigya_token", idToken).toString().toByteArray(Charsets.UTF_8)
        val (code, resp) = execute("POST", UconnectBrand.TOKEN_URL, headers, body)
        if (code !in 200..299) throw UconnectException("Troca de token falhou ($code): $resp")
        val json = JSONObject(resp)
        val token = firstNonBlank(json, "token", "Token", "accessToken", "access_token")
        val identityId = firstNonBlank(json, "IdentityId", "identityId", "identity_id")
        if (token.isBlank() || identityId.isBlank()) {
            val keys = json.keys().asSequence().toList().joinToString(",")
            throw UconnectException("Troca de token incompleta. Campos recebidos: [$keys]")
        }
        return token to identityId
    }

    private fun gigyaError(json: JSONObject): String {
        val code = json.optInt("errorCode", -1)
        val message = json.optString("errorMessage")
        val details = json.optString("errorDetails")
        return listOf("código $code", message, details).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun firstNonBlank(json: JSONObject, vararg names: String): String {
        for (name in names) {
            val value = json.optString(name)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun cognitoCredentials(identityId: String, token: String): AwsCreds {
        val headers = mapOf(
            "Content-Type" to "application/x-amz-json-1.1",
            "X-Amz-Target" to "AWSCognitoIdentityService.GetCredentialsForIdentity",
        )
        val body = JSONObject()
            .put("IdentityId", identityId)
            .put("Logins", JSONObject().put("cognito-identity.amazonaws.com", token))
            .toString().toByteArray(Charsets.UTF_8)
        val (code, resp) = execute("POST", UconnectBrand.COGNITO_URL, headers, body)
        if (code !in 200..299) throw UconnectException("Cognito falhou ($code): $resp")
        val creds = JSONObject(resp).optJSONObject("Credentials")
            ?: throw UconnectException("Cognito sem credenciais.")
        return AwsCreds(
            creds.getString("AccessKeyId"),
            creds.getString("SecretKey"),
            creds.getString("SessionToken"),
        )
    }

    private fun signed(
        method: String,
        urlStr: String,
        apiKey: String,
        body: ByteArray,
        session: UconnectSession,
    ): Pair<Int, String> {
        val url = URL(urlStr)
        val sig = AwsSigV4.headers(
            method, url, "execute-api", UconnectBrand.REGION,
            session.creds.accessKey, session.creds.secretKey, session.creds.sessionToken, body,
        )
        val headers = defaultHeaders(apiKey) + sig + ("content-type" to "application/json")
        return execute(method, urlStr, headers, if (method == "GET") null else body)
    }

    private fun defaultHeaders(apiKey: String): Map<String, String> = mapOf(
        "x-clientapp-name" to "CWP",
        "x-clientapp-version" to "1.0",
        "clientrequestid" to (randomHex(10) + System.currentTimeMillis().toString().takeLast(6)),
        "x-api-key" to apiKey,
        "locale" to UconnectBrand.LOCALE,
        "x-originator-type" to "web",
    )

    private fun execute(method: String, urlStr: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, String> {
        val parsed = URL(urlStr)
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) doOutput = true
        }
        try {
            try {
                connection.connect()
            } catch (e: IOException) {
                throw networkError(e, sent = false)
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return code to text
            } catch (e: IOException) {
                throw networkError(e, sent = true)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun networkError(error: IOException, sent: Boolean): UconnectNetworkException {
        val message = when {
            !sent -> "Sem conexão com o servidor da Fiat. Verifique a internet e tente novamente."
            error is SocketTimeoutException -> "O servidor da Fiat não respondeu a tempo."
            else -> "A conexão com o servidor da Fiat caiu antes da resposta."
        }
        return UconnectNetworkException(message, sent)
    }

    private fun truncate(value: String, max: Int = 400): String =
        if (value.length <= max) value else value.substring(0, max) + "…"

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun subjectOf(jwt: String): String {
        val parts = jwt.split(".")
        if (parts.size < 2) throw UconnectException("JWT inválido.")
        val payload = String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8)
        return JSONObject(payload).optString("sub").ifBlank { throw UconnectException("JWT sem sub.") }
    }

    private fun pad(value: String): String = when (value.length % 4) {
        2 -> "$value=="
        3 -> "$value="
        else -> value
    }

    private fun randomHex(bytes: Int): String =
        (0 until bytes).joinToString("") { "%02x".format(Random.nextInt(256)) }
}
