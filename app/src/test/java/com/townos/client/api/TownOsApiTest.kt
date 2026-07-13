package com.townos.client.api

import com.townos.client.net.TownOsTrust
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BaseUrlTest {

    @Test
    fun `a bare host gets the control-plane port and http`() {
        // What a person actually types. The control API terminates no TLS, so
        // https would just fail.
        val url = TownOsApi.parseBaseUrl("192.168.122.50")!!
        assertEquals("http", url.scheme)
        assertEquals(5309, url.port)
    }

    @Test
    fun `an explicit port is respected`() {
        val url = TownOsApi.parseBaseUrl("192.168.122.50:8080")!!
        assertEquals(8080, url.port)
    }

    @Test
    fun `a full URL is respected`() {
        val url = TownOsApi.parseBaseUrl("http://box.home:5309")!!
        assertEquals("box.home", url.host)
        assertEquals(5309, url.port)
    }

    @Test
    fun `a trailing slash does not produce a double slash in paths`() {
        val url = TownOsApi.parseBaseUrl("192.168.122.50/")!!
        assertEquals("http://192.168.122.50:5309/networks", url.newBuilder().addPathSegments("networks").build().toString())
    }

    @Test
    fun `garbage is rejected rather than silently dialed`() {
        assertNull(TownOsApi.parseBaseUrl(""))
        assertNull(TownOsApi.parseBaseUrl("   "))
    }

    @Test
    fun `https is preserved when the operator fronts the API with TLS`() {
        val url = TownOsApi.parseBaseUrl("https://box.example:443")!!
        assertEquals("https", url.scheme)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val url = TownOsApi.parseBaseUrl("  192.168.122.50  ")!!
        assertEquals("192.168.122.50", url.host)
        assertEquals(5309, url.port)
    }

    @Test
    fun `a hostname under a private TLD works`() {
        // Boxes are commonly reached as e.g. town-os.home.
        val url = TownOsApi.parseBaseUrl("town-os.home")!!
        assertEquals("town-os.home", url.host)
        assertEquals(5309, url.port)
    }

    @Test
    fun `the default port matches the systemcontroller's -listen default`() {
        assertEquals(5309, TownOsApi.DEFAULT_PORT)
    }
}

/**
 * Wire-level tests against a mock server. These pin the request shape (bearer
 * header, JSON body, path) and the response decoding, which is where a
 * mismatch with the Go structs would show up.
 */
class TownOsApiWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TownOsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = TownOsApi(server.url("/"), TownOsTrust.systemOnly())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authenticate posts credentials and reads back the token`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token":"jwt-here","account":{"username":"erikh","admin":true}}"""),
        )

        val response = api.authenticate("erikh", "hunter2")

        assertEquals("jwt-here", response.token)
        assertTrue(response.account!!.admin)

        val request = server.takeRequest()
        assertEquals("/account/authenticate", request.path)
        assertEquals("""{"username":"erikh","password":"hunter2"}""", request.body.readUtf8())
    }

    @Test
    fun `listNetworks sends the bearer token and decodes the flat shape`() {
        // NetworkView embeds account.Network in Go, so the JSON is flat — there
        // is no nested "network" object. Decoding a nested shape here would be
        // the bug.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"name":"fart","tld":"fart","subnet":"10.90.12.0/24","address":"10.90.12.1/24",
                     "public_key":"abc","listen_port":51837,"enabled":true,
                     "peer_count":2,"interface":"town1a2b","running":true}]""",
            ),
        )

        val networks = api.listNetworks("jwt-here")

        assertEquals(1, networks.size)
        assertEquals("fart", networks[0].name)
        assertEquals("10.90.12.0/24", networks[0].subnet)
        assertEquals(51837, networks[0].listenPort)
        assertEquals(2, networks[0].peerCount)
        assertTrue(networks[0].running)

        assertEquals("Bearer jwt-here", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `addPeer sends our public key so the private key never crosses the wire`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"peer":{"network":"fart","allowed_ip":"10.90.12.7/32"},
                    "config":"[Interface]\nPrivateKey = REPLACE_WITH_YOUR_PRIVATE_KEY\n"}""",
            ),
        )

        val response = api.addPeer("jwt", "fart", "pixel", "our-pubkey")

        // The box only generates (and returns) a private key when we DON'T send
        // a public one. Sending ours is what keeps it off the wire.
        assertNull(response.privateKey)
        assertTrue(response.config.contains("REPLACE_WITH_YOUR_PRIVATE_KEY"))
        assertEquals("10.90.12.7/32", response.peer!!.allowedIp)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""public_key":"our-pubkey""""))
    }

    @Test
    fun `a 401 is surfaced as unauthorized, since a box reboot invalidates tokens`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid credentials"}"""))

        val error = runCatching { api.listNetworks("stale-token") }.exceptionOrNull()

        assertTrue(error is TownOsApi.ApiException)
        assertTrue((error as TownOsApi.ApiException).unauthorized)
        assertEquals("invalid credentials", error.message)
    }

    @Test
    fun `a 403 is surfaced as forbidden, since peer creation is admin-only`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"admin required"}"""))

        val error = runCatching { api.addPeer("jwt", "fart", "pixel", "pk") }
            .exceptionOrNull() as TownOsApi.ApiException

        assertTrue(error.forbidden)
    }

    @Test
    fun `the CA is fetched unauthenticated and returned verbatim`() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(pem))

        assertEquals(pem, String(api.fetchCaPem()))

        val request = server.takeRequest()
        assertEquals("/tls/ca.crt", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `unknown fields in a response do not break decoding`() {
        // The box adds fields over time; an older client must not hard-fail.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""[{"name":"fart","tld":"fart","brand_new_field":42}]"""),
        )

        assertEquals("fart", api.listNetworks("jwt")[0].name)
    }

    @Test
    fun `dnsStatus reads the TLD, the source of truth for package names`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"enabled":true,"running":true,"tld":"fart","record_count":4}"""),
        )

        assertEquals("fart", api.dnsStatus("jwt").tld)
        assertEquals("/dns/status", server.takeRequest().path)
    }

    @Test
    fun `removePeer posts the network and public key`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        api.removePeer("jwt", "fart", "PUBKEY")

        val request = server.takeRequest()
        assertEquals("/networks/peers/remove", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""network":"fart""""))
        assertTrue(body.contains(""""public_key":"PUBKEY""""))
    }

    @Test
    fun `requests are sent as JSON`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"t"}"""))

        api.authenticate("u", "p")

        assertTrue(
            server.takeRequest().getHeader("Content-Type").orEmpty().startsWith("application/json"),
        )
    }

    @Test
    fun `a non-problem-json error body still surfaces something useful`() {
        // Not every failure comes from the RFC 7807 handler — a proxy or a panic
        // can return plain text. Swallowing it would leave the user with a bare
        // status code.
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val error = runCatching { api.listNetworks("jwt") }
            .exceptionOrNull() as TownOsApi.ApiException

        assertEquals(500, error.code)
        assertTrue(error.message!!.contains("upstream exploded"))
    }

    @Test
    fun `an empty error body falls back to the status code`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody(""))

        val error = runCatching { api.dnsStatus("jwt") }
            .exceptionOrNull() as TownOsApi.ApiException

        assertEquals(502, error.code)
        assertTrue(error.message!!.contains("502"))
    }

    @Test
    fun `a 500 is neither unauthorized nor forbidden`() {
        // Guards against a lazy `code != 200` classification that would tell the
        // user to log in again when the box merely fell over.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))

        val error = runCatching { api.listNetworks("jwt") }
            .exceptionOrNull() as TownOsApi.ApiException

        assertFalse(error.unauthorized)
        assertFalse(error.forbidden)
    }

    @Test
    fun `an empty network list decodes to an empty list, not an error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertTrue(api.listNetworks("jwt").isEmpty())
    }
}
