package com.townos.client.api

// The reified encodeToString(value) extension; without it the call resolves to
// the member overload expecting a SerializationStrategy.
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding of the Go wire types.
 *
 * A field-name mismatch against the Go `json` tags does not fail loudly — with
 * `ignoreUnknownKeys` it silently yields a default (empty subnet, port 0, false).
 * So the tags are pinned here.
 */
class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Network decodes the snake_case tags Go emits`() {
        val network = json.decodeFromString<Network>(
            """{"name":"fart","tld":"fart","subnet":"10.90.12.0/24","address":"10.90.12.1/24",
                "public_key":"abc=","listen_port":51837,"enabled":true,
                "peer_count":3,"interface":"town1a2b","running":true}""",
        )

        assertEquals("fart", network.name)
        assertEquals("fart", network.tld)
        assertEquals("10.90.12.0/24", network.subnet)
        assertEquals("abc=", network.publicKey)
        assertEquals(51837, network.listenPort)
        assertEquals(3, network.peerCount)
        assertEquals("town1a2b", network.`interface`)
        assertTrue(network.enabled)
        assertTrue(network.running)
    }

    @Test
    fun `the default home network is not joinable`() {
        // applyNetworkTransport() returns early for "home": it has no WireGuard
        // interface, no overlay subnet and no peers. Adding a peer to it
        // "succeeds" and returns a config, but nothing is ever listening — so it
        // must never be offered to the user.
        val home = json.decodeFromString<Network>("""{"name":"home","tld":"home"}""")

        assertFalse(home.joinable)
        assertEquals("home", Network.DEFAULT_NETWORK_NAME)
    }

    @Test
    fun `a non-default network is joinable`() {
        val net = json.decodeFromString<Network>("""{"name":"fart","tld":"fart"}""")
        assertTrue(net.joinable)
    }

    @Test
    fun `AddPeerResponse with a server-generated key exposes the private key`() {
        // This is the path we deliberately avoid — but if it happens, we must be
        // able to see it rather than decode it away.
        val response = json.decodeFromString<AddPeerResponse>(
            """{"peer":{"network":"fart","public_key":"pk","allowed_ip":"10.90.12.7/32"},
                "private_key":"LEAKED","config":"[Interface]"}""",
        )

        assertEquals("LEAKED", response.privateKey)
        assertEquals("10.90.12.7/32", response.peer!!.allowedIp)
    }

    @Test
    fun `AddPeerResponse omits private_key when we supplied our own public key`() {
        val response = json.decodeFromString<AddPeerResponse>(
            """{"peer":{"network":"fart"},"config":"[Interface]"}""",
        )
        assertEquals(null, response.privateKey)
    }

    @Test
    fun `AddPeerRequest serializes public_key, never a private one`() {
        val encoded = json.encodeToString(AddPeerRequest("fart", "pixel", "OURPUBKEY"))

        assertTrue(encoded.contains(""""public_key":"OURPUBKEY""""))
        assertFalse(encoded.contains("private"))
    }

    @Test
    fun `DnsStatus decodes the TLD, which is the source of truth for names`() {
        val status = json.decodeFromString<DnsStatus>(
            """{"enabled":true,"running":true,"tld":"fart","record_count":12}""",
        )

        assertEquals("fart", status.tld)
        assertEquals(12, status.recordCount)
        assertTrue(status.running)
    }

    @Test
    fun `missing optional fields fall back to defaults rather than throwing`() {
        // The box's responses vary by version; a sparse object must still decode.
        val network = json.decodeFromString<Network>("""{"name":"x","tld":"y"}""")

        assertEquals("", network.subnet)
        assertEquals(0, network.listenPort)
        assertFalse(network.running)
    }
}
