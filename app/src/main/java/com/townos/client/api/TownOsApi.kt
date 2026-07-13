package com.townos.client.api

import com.townos.client.net.TownOsTrust
// The reified `encodeToString(value)` extension. Without this import the call
// resolves to the member overload that expects a SerializationStrategy.
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Town OS control API.
 *
 * Auth is a bearer JWT from POST /account/authenticate. Two things about that
 * token are worth knowing, because they change what the app must do:
 *
 *  - The signing key is regenerated on every systemcontroller start and the
 *    sessions table is wiped, so **every box reboot invalidates our token**. A
 *    401 is therefore normal and means "log in again", not "wrong password".
 *  - Every mutating /networks route requires an *admin* account, so enrolling a
 *    device needs admin credentials. There is no device-enrollment or invite
 *    flow in Town OS today.
 */
class TownOsApi(
    private val baseUrl: HttpUrl,
    private val trust: TownOsTrust,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { trust.applyTo(this) }
        .build()

    class ApiException(val code: Int, message: String) : IOException(message) {
        /** The box wipes sessions on restart, so a 401 usually just means "log in again". */
        val unauthorized: Boolean get() = code == 401
        val forbidden: Boolean get() = code == 403
    }

    fun authenticate(username: String, password: String): AuthenticateResponse =
        post("account/authenticate", AuthenticateRequest(username, password), token = null)

    fun listNetworks(token: String): List<Network> =
        get("networks", token)

    fun addPeer(token: String, network: String, deviceName: String, publicKey: String): AddPeerResponse =
        post("networks/peers/add", AddPeerRequest(network, deviceName, publicKey), token)

    fun removePeer(token: String, network: String, publicKey: String) {
        post<RemovePeerRequest, Unit>("networks/peers/remove", RemovePeerRequest(network, publicKey), token)
    }

    fun dnsStatus(token: String): DnsStatus =
        get("dns/status", token)

    /**
     * The box's local CA, in PEM. Unauthenticated by design — a CA certificate
     * is handed to every TLS client during the handshake, so it is public.
     *
     * This is what lets the app speak HTTPS to packages behind the box's :443
     * ingress: their leaves are issued by this CA, which no Android trust store
     * knows about.
     */
    fun fetchCaPem(): ByteArray {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("tls/ca.crt").build())
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException(response.code, "fetch CA: HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw IOException("fetch CA: empty body")
        }
    }

    private inline fun <reified T> get(path: String, token: String): T {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request)
    }

    private inline fun <reified Req, reified Res> post(path: String, body: Req, token: String?): Res {
        val payload = json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .post(payload)
            .build()
        return execute(request)
    }

    private inline fun <reified T> execute(request: Request): T {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, problemDetail(body) ?: "HTTP ${response.code}")
            }
            if (T::class == Unit::class) return Unit as T
            return json.decodeFromString(body)
        }
    }

    /**
     * Errors come back as RFC 7807 problem+json (ProblemDetailHTTPErrorHandler),
     * where the human-readable message is in "detail". Fall back to the raw body
     * if it is not the shape we expect.
     */
    fun problemDetail(body: String): String? = runCatching {
        json.parseToJsonElement(body).let { element ->
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("detail")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }
    }.getOrNull() ?: body.takeIf { it.isNotBlank() }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Default control-plane port (systemcontroller `-listen`). */
        const val DEFAULT_PORT = 5309

        /**
         * Accept what a person would actually type: "192.168.122.50",
         * "192.168.122.50:5309", or a full URL. Bare hosts get http + :5309,
         * because the control API terminates no TLS.
         */
        fun parseBaseUrl(input: String): HttpUrl? {
            val trimmed = input.trim().removeSuffix("/")
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            val url = withScheme.toHttpUrlOrNull() ?: return null
            // Only default the port when the user did not supply one. HttpUrl
            // reports 80 for a scheme-default http URL, so compare against that.
            val explicitPort = withScheme.substringAfter("://").contains(':')
            return if (explicitPort) url else url.newBuilder().port(DEFAULT_PORT).build()
        }
    }
}
