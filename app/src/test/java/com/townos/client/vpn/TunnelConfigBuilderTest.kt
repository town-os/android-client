package com.townos.client.vpn

import com.wireguard.config.InetNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The DNS wiring.
 *
 * This is the one part of the app that, if wrong, fails *silently*: the tunnel
 * comes up, traffic flows, the UI says "Connected" — and only name resolution is
 * broken, in a way that looks like the box's fault. So it is tested hard, in both
 * directions.
 */
class TunnelConfigBuilderTest {

    // The exact shape renderPeerDeviceConfig() emits, including the placeholder
    // the box writes when the device supplied its own public key.
    private fun boxConfig(
        dns: String = "10.90.12.1",
        allowedIps: String = "10.90.12.0/24",
        endpoint: String? = "192.168.122.50:51837",
        keepalive: String? = "25",
    ) = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${TunnelConfigBuilder.PRIVATE_KEY_PLACEHOLDER}")
        appendLine("Address = 10.90.12.7/32")
        appendLine("DNS = $dns")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = 1ZUCUZ0BFOtT5EIx0FDBLrGvLfLxK0qHhHiFGT8SPmY=")
        endpoint?.let { appendLine("Endpoint = $it") }
        appendLine("AllowedIPs = $allowedIps")
        keepalive?.let { appendLine("PersistentKeepalive = $it") }
    }

    private fun ourKey() = TunnelController.generateKeyPair().privateKey.toBase64()

    // ---- ensureDnsRouted: the core rule ------------------------------------

    @Test
    fun `overlay resolver is already covered by AllowedIPs and adds no route`() {
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("10.90.12.0/24")),
            listOf(InetAddress.getByName("10.90.12.1")),
        )
        assertEquals(listOf(InetNetwork.parse("10.90.12.0/24")), result)
    }

    @Test
    fun `LAN resolver outside AllowedIPs gets a host route into the tunnel`() {
        // rolodex on a stock box binds the default-route interface, not the
        // overlay — so the resolver is the box's LAN address. It must still be
        // routed through the tunnel, or the query leaves with a non-overlay
        // source IP and rolodex answers with the LAN view (addresses we cannot
        // route to) instead of the scoped overlay view.
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("10.90.12.0/24")),
            listOf(InetAddress.getByName("192.168.122.50")),
        )
        assertTrue(result.contains(InetNetwork.parse("192.168.122.50/32")))
        assertTrue(result.contains(InetNetwork.parse("10.90.12.0/24")))
    }

    @Test
    fun `a full-tunnel default route already covers every resolver`() {
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("0.0.0.0/0")),
            listOf(InetAddress.getByName("192.168.122.50")),
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `every resolver gets routed, not just the first`() {
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("10.90.12.0/24")),
            listOf(
                InetAddress.getByName("192.168.122.50"),
                InetAddress.getByName("192.168.122.51"),
            ),
        )
        assertTrue(result.contains(InetNetwork.parse("192.168.122.50/32")))
        assertTrue(result.contains(InetNetwork.parse("192.168.122.51/32")))
    }

    @Test
    fun `an IPv6 resolver gets a 128-bit host route, not a 32-bit one`() {
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("10.90.12.0/24")),
            listOf(InetAddress.getByName("fd00::1")),
        )
        assertTrue(result.any { it.mask == 128 && it.address.hostAddress?.contains(":") == true })
    }

    @Test
    fun `a v4 resolver is not considered covered by a v6 route`() {
        // Guards the family check in contains(): comparing raw address bytes
        // across families would be nonsense, and a missing route here means DNS
        // silently leaves the tunnel.
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("fd00::/64")),
            listOf(InetAddress.getByName("192.168.122.50")),
        )
        assertTrue(result.contains(InetNetwork.parse("192.168.122.50/32")))
    }

    @Test
    fun `a resolver just outside the prefix is routed - boundary`() {
        // 10.90.13.1 is NOT in 10.90.12.0/24. An off-by-one in the mask
        // comparison would wrongly treat it as covered and drop the route.
        val result = TunnelConfigBuilder.ensureDnsRouted(
            listOf(InetNetwork.parse("10.90.12.0/24")),
            listOf(InetAddress.getByName("10.90.13.1")),
        )
        assertTrue(result.contains(InetNetwork.parse("10.90.13.1/32")))
    }

    @Test
    fun `a non-byte-aligned prefix is honoured - the overlay 10 64 0 0 slash 10`() {
        // Town OS derives every overlay subnet from 10.64.0.0/10 — a /10 is not
        // byte-aligned, so the partial-byte masking in contains() is load-bearing.
        val allowed = listOf(InetNetwork.parse("10.64.0.0/10"))

        // Inside /10 (10.64–10.127): no extra route.
        assertEquals(
            1,
            TunnelConfigBuilder.ensureDnsRouted(
                allowed,
                listOf(InetAddress.getByName("10.90.12.1")),
            ).size,
        )
        // Outside /10 (10.200.x is not in 10.64.0.0/10): must be routed.
        assertTrue(
            TunnelConfigBuilder.ensureDnsRouted(
                allowed,
                listOf(InetAddress.getByName("10.200.0.1")),
            ).contains(InetNetwork.parse("10.200.0.1/32")),
        )
    }

    // ---- clampToOverlay: town-os traffic only -------------------------------

    private fun ifaceAddr(cidr: String = "10.90.12.7/32") = listOf(InetNetwork.parse(cidr))

    @Test
    fun `an overlay subnet is kept as-is`() {
        val result = TunnelConfigBuilder.clampToOverlay(
            listOf(InetNetwork.parse("10.90.12.0/24")),
            ifaceAddr(),
        )
        assertEquals(listOf(InetNetwork.parse("10.90.12.0/24")), result)
    }

    @Test
    fun `a default route is dropped and the overlay is synthesized from our address`() {
        // The exact failure we are fixing: a stale full-tunnel enrollment. The
        // box no longer sends 0.0.0.0/0, but a phone that enrolled before the fix
        // still has it, and routing all traffic into a non-NATing tunnel takes the
        // whole phone offline.
        val result = TunnelConfigBuilder.clampToOverlay(
            listOf(InetNetwork.parse("0.0.0.0/0")),
            ifaceAddr(),
        )
        assertEquals(listOf(InetNetwork.parse("10.90.12.0/24")), result)
        assertFalse(result.contains(InetNetwork.parse("0.0.0.0/0")))
    }

    @Test
    fun `an overlay route survives alongside a default route`() {
        val result = TunnelConfigBuilder.clampToOverlay(
            listOf(InetNetwork.parse("10.90.12.0/24"), InetNetwork.parse("0.0.0.0/0")),
            ifaceAddr(),
        )
        assertEquals(listOf(InetNetwork.parse("10.90.12.0/24")), result)
    }

    @Test
    fun `a non-overlay route is dropped, not routed into the tunnel`() {
        // A LAN or public prefix the box has no business routing for us: the
        // phone reaches those on its own, never through the box.
        val result = TunnelConfigBuilder.clampToOverlay(
            listOf(InetNetwork.parse("192.168.1.0/24")),
            ifaceAddr(),
        )
        assertEquals(listOf(InetNetwork.parse("10.90.12.0/24")), result)
    }

    @Test
    fun `a route broader than the overlay range is rejected`() {
        // 10.0.0.0/8 contains the overlay but also 10.0/10.1 — the ranges consumer
        // routers hand out. A prefix shorter than the /10 gate is not town-os.
        val result = TunnelConfigBuilder.clampToOverlay(
            listOf(InetNetwork.parse("10.0.0.0/8")),
            ifaceAddr(),
        )
        assertEquals(listOf(InetNetwork.parse("10.90.12.0/24")), result)
    }

    // ---- build(): end to end ------------------------------------------------

    @Test
    fun `a full-tunnel config is clamped back to the overlay`() {
        val config = TunnelConfigBuilder.build(
            boxConfig(allowedIps = "0.0.0.0/0"),
            privateKey = ourKey(),
        )
        val allowed = config.peers.first().allowedIps
        assertFalse(allowed.contains(InetNetwork.parse("0.0.0.0/0")))
        assertTrue(allowed.contains(InetNetwork.parse("10.90.12.0/24")))
    }

    @Test
    fun `builds a usable config from the box's peer output`() {
        val config = TunnelConfigBuilder.build(
            peerConfig = boxConfig(),
            privateKey = ourKey(),
            searchDomain = "fart",
            dnsServerOverride = "192.168.122.50",
        )

        assertEquals(
            setOf(InetAddress.getByName("192.168.122.50")),
            config.getInterface().dnsServers,
        )
        assertTrue(config.getInterface().dnsSearchDomains.contains("fart"))
        assertTrue(
            config.peers.first().allowedIps.contains(InetNetwork.parse("192.168.122.50/32")),
        )
    }

    @Test
    fun `with no override the box's own DNS line is used`() {
        val config = TunnelConfigBuilder.build(boxConfig(), privateKey = ourKey())

        assertEquals(
            setOf(InetAddress.getByName("10.90.12.1")),
            config.getInterface().dnsServers,
        )
    }

    @Test
    fun `the override replaces the box's resolver rather than joining it`() {
        // Leaving an unreachable overlay .1 alongside a working LAN address would
        // add a resolver timeout to every single lookup.
        val config = TunnelConfigBuilder.build(
            boxConfig(),
            privateKey = ourKey(),
            dnsServerOverride = "192.168.122.50",
        )

        assertFalse(
            config.getInterface().dnsServers.contains(InetAddress.getByName("10.90.12.1")),
        )
        assertEquals(1, config.getInterface().dnsServers.size)
    }

    @Test
    fun `a comma-separated override installs every resolver and routes each`() {
        val config = TunnelConfigBuilder.build(
            boxConfig(),
            privateKey = ourKey(),
            dnsServerOverride = "192.168.122.50, 192.168.122.51",
        )

        assertEquals(2, config.getInterface().dnsServers.size)
        val allowed = config.peers.first().allowedIps
        assertTrue(allowed.contains(InetNetwork.parse("192.168.122.50/32")))
        assertTrue(allowed.contains(InetNetwork.parse("192.168.122.51/32")))
    }

    @Test
    fun `the placeholder private key without our key is a hard error`() {
        // Silently building a config with a bogus key would produce a tunnel that
        // never handshakes, with no clue why.
        val error = assertThrows(IllegalArgumentException::class.java) {
            TunnelConfigBuilder.build(boxConfig(), privateKey = null)
        }
        assertTrue(error.message!!.contains("private key"))
    }

    @Test
    fun `our private key is substituted for the placeholder`() {
        val key = ourKey()
        val config = TunnelConfigBuilder.build(boxConfig(), privateKey = key)

        assertEquals(key, config.getInterface().keyPair.privateKey.toBase64())
    }

    @Test
    fun `the endpoint override wins over the box's endpoint`() {
        // The box hands out its polled external IP with no port forward behind
        // it, so the user needs to be able to correct this.
        val config = TunnelConfigBuilder.build(
            boxConfig(endpoint = "203.0.113.9:51837"),
            privateKey = ourKey(),
            endpointOverride = "192.168.122.50:51820",
        )

        val endpoint = config.peers.first().endpoint.get()
        assertEquals("192.168.122.50", endpoint.host)
        assertEquals(51820, endpoint.port)
    }

    @Test
    fun `a config with no Endpoint still builds`() {
        // renderPeerDeviceConfig omits Endpoint entirely when the box knows
        // neither its external nor its internal IP.
        val config = TunnelConfigBuilder.build(
            boxConfig(endpoint = null),
            privateKey = ourKey(),
            endpointOverride = "192.168.122.50:51820",
        )
        assertTrue(config.peers.first().endpoint.isPresent)
    }

    @Test
    fun `keepalive is defaulted when the box omits it`() {
        // Without a keepalive the NAT return path goes cold and inbound traffic —
        // including DNS responses — stops arriving.
        val config = TunnelConfigBuilder.build(
            boxConfig(keepalive = null),
            privateKey = ourKey(),
        )
        assertEquals(25, config.peers.first().persistentKeepalive.get())
    }

    @Test
    fun `a blank search domain is not installed`() {
        val config = TunnelConfigBuilder.build(
            boxConfig(),
            privateKey = ourKey(),
            searchDomain = "   ",
        )
        assertTrue(config.getInterface().dnsSearchDomains.none { it.isBlank() })
    }

    @Test
    fun `a blank override falls back to the box's resolver`() {
        val config = TunnelConfigBuilder.build(
            boxConfig(),
            privateKey = ourKey(),
            dnsServerOverride = "   ",
        )
        assertEquals(
            setOf(InetAddress.getByName("10.90.12.1")),
            config.getInterface().dnsServers,
        )
    }

    @Test
    fun `the split tunnel is preserved - we never widen AllowedIPs to a default route`() {
        // Adding 0.0.0.0/0 would silently route ALL the phone's traffic through
        // the box. The box hands out a split tunnel; we only add host routes.
        val config = TunnelConfigBuilder.build(
            boxConfig(),
            privateKey = ourKey(),
            dnsServerOverride = "192.168.122.50",
        )
        assertFalse(
            config.peers.first().allowedIps.contains(InetNetwork.parse("0.0.0.0/0")),
        )
    }

    @Test
    fun `a hostname as a resolver is rejected rather than silently resolved`() {
        // InetAddress.getByName would happily do a DNS lookup on the phone's
        // normal network — the exact thing this app exists to avoid.
        assertThrows(IllegalArgumentException::class.java) {
            TunnelConfigBuilder.build(
                boxConfig(),
                privateKey = ourKey(),
                dnsServerOverride = "dns.google",
            )
        }
    }
}
