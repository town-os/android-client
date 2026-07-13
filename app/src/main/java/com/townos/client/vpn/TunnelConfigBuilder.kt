package com.townos.client.vpn

import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.io.BufferedReader
import java.io.StringReader
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Turns the wg-quick config the box hands out into a tunnel config whose DNS
 * actually resolves Town OS names.
 *
 * This is the part that is easy to get wrong, so it is worth being explicit
 * about what the box gives us and what has to be true for DNS to work.
 *
 * The box renders (renderPeerDeviceConfig, controller_networks_reconcile.go):
 *
 *     [Interface]
 *     PrivateKey = <server-generated, or the literal REPLACE_WITH_YOUR_PRIVATE_KEY>
 *     Address    = 10.90.12.7/32
 *     DNS        = 10.90.12.1          <- the box's ".1" overlay address
 *
 *     [Peer]
 *     PublicKey  = <network public key>
 *     Endpoint   = <box ip>:51837
 *     AllowedIPs = 10.90.12.0/24       <- SPLIT TUNNEL: the overlay only
 *     PersistentKeepalive = 25
 *
 * Two properties of rolodex (the box's resolver) drive everything below:
 *
 *  1. **It answers by source IP.** A query arriving from an overlay address
 *     (10.64.0.0/10) gets that network's scoped view — the network's own TLD,
 *     with overlay addresses in the answers. A query from anywhere else gets the
 *     global/LAN view. An overlay source that is not joined to a scope is
 *     REFUSED outright. So our DNS queries *must* leave through the tunnel; if
 *     they go out over Wi-Fi instead, we get the wrong view or no answer.
 *
 *  2. **It only listens where it is bound.** On a real box (install repo,
 *     scripts/rolodex-config.sh) rolodex binds 127.0.0.2, ::1, and every
 *     global-scope address on the *default-route* interface — e.g.
 *     192.168.122.50 on the QEMU dev VM. The WireGuard interface is not the
 *     default route, so the overlay ".1" the config points at is not
 *     necessarily bound.
 *
 * Together those mean the DNS server address must be (a) one rolodex actually
 * listens on, and (b) routed *into* the tunnel so the query carries our overlay
 * source IP. Those two can pull in opposite directions, which is why
 * [dnsServerOverride] exists: point DNS at the box's LAN address if that is
 * where rolodex is bound, and we will still route it through the tunnel so the
 * split-horizon logic sees an overlay source.
 *
 * [ensureDnsRouted] is what makes that safe: every DNS server we install gets a
 * host route (/32, or /128 for v6) added to AllowedIPs. For the overlay ".1"
 * that is a no-op — it already falls inside the overlay /24. For a LAN address
 * it is the whole ballgame.
 */
object TunnelConfigBuilder {

    /** The box emits this when the device supplied its own public key. */
    const val PRIVATE_KEY_PLACEHOLDER = "REPLACE_WITH_YOUR_PRIVATE_KEY"

    /**
     * Build a [Config] from the box's peer config.
     *
     * @param peerConfig the `config` string from POST /networks/peers/add.
     * @param privateKey our locally generated private key. Required when the
     *   box emitted the placeholder (i.e. we enrolled with our own public key,
     *   which is the only path where the private key never crosses the wire).
     * @param searchDomain the network's TLD (from GET /dns/tld). Installed as a
     *   DNS search domain so a bare `gitea` resolves; package FQDNs are
     *   `<pkg>.<repo>.<tld>`, so this mostly helps humans typing short names.
     * @param dnsServerOverride use this resolver instead of the config's `DNS =`
     *   line. Set it when rolodex is bound somewhere other than the overlay .1.
     * @param endpointOverride use this `host:port` instead of the config's
     *   `Endpoint =`. The box omits Endpoint entirely when it knows neither its
     *   external nor internal IP, and it will happily hand out a public IP with
     *   no port forward behind it, so the user needs a way to correct it.
     */
    fun build(
        peerConfig: String,
        privateKey: String? = null,
        searchDomain: String? = null,
        dnsServerOverride: String? = null,
        endpointOverride: String? = null,
    ): Config {
        // The placeholder is not a valid base64 key, so it has to go before the
        // parser ever sees it.
        val substituted = if (peerConfig.contains(PRIVATE_KEY_PLACEHOLDER)) {
            require(!privateKey.isNullOrBlank()) {
                "the box returned a config with no private key; enroll with a locally generated key"
            }
            peerConfig.replace(PRIVATE_KEY_PLACEHOLDER, privateKey)
        } else {
            peerConfig
        }

        val parsed = Config.parse(BufferedReader(StringReader(substituted)))

        val dnsServers = resolveDnsServers(parsed.getInterface(), dnsServerOverride)

        val iface = Interface.Builder().apply {
            parsePrivateKey(parsed.getInterface().keyPair.privateKey.toBase64())
            addAddresses(parsed.getInterface().addresses)
            dnsServers.forEach { addDnsServer(it) }
            // Keep any search domains the box already set, then add the TLD.
            parsed.getInterface().dnsSearchDomains.forEach { addDnsSearchDomain(it) }
            searchDomain?.takeIf { it.isNotBlank() }?.let { addDnsSearchDomain(it) }
        }.build()

        val peers = parsed.peers.map { peer ->
            Peer.Builder().apply {
                setPublicKey(peer.publicKey)
                peer.preSharedKey.ifPresent { setPreSharedKey(it) }

                val endpoint = endpointOverride?.takeIf { it.isNotBlank() }
                if (endpoint != null) {
                    parseEndpoint(endpoint)
                } else {
                    peer.endpoint.ifPresent { setEndpoint(it) }
                }

                // PersistentKeepalive matters here: the box is usually behind
                // NAT relative to a phone on a mobile network, and without a
                // keepalive the return path goes cold and inbound traffic
                // (including DNS responses) stops arriving.
                setPersistentKeepalive(peer.persistentKeepalive.orElse(25))

                ensureDnsRouted(peer.allowedIps, dnsServers).forEach { addAllowedIp(it) }
            }.build()
        }

        return Config.Builder()
            .setInterface(iface)
            .addPeers(peers)
            .build()
    }

    /**
     * The DNS servers to install, honouring an override.
     *
     * An override replaces the config's servers outright rather than adding to
     * them. Android queries its DNS servers in order but will fall back to the
     * next one on failure, so leaving the box's unreachable overlay .1 in the
     * list alongside a working LAN address would just add a timeout to every
     * lookup.
     */
    private fun resolveDnsServers(iface: Interface, override: String?): List<InetAddress> {
        val fromOverride = override
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { InetAddresses.parse(it) }
            ?: emptyList()

        return fromOverride.ifEmpty { iface.dnsServers.toList() }
    }

    /**
     * AllowedIPs, guaranteed to route every DNS server through the tunnel.
     *
     * A DNS server already covered by an existing AllowedIP (the common case:
     * the overlay .1 sits inside the overlay /24) is left alone. Anything else
     * gets a host route. Without this, a DNS server outside AllowedIPs is
     * queried over the phone's normal network — which either fails to reach a
     * loopback/LAN-bound rolodex at all, or reaches it with a non-overlay source
     * IP and gets the LAN view (LAN addresses we cannot route to) instead of the
     * scoped overlay view.
     */
    internal fun ensureDnsRouted(
        allowedIps: Collection<InetNetwork>,
        dnsServers: Collection<InetAddress>,
    ): List<InetNetwork> {
        val result = allowedIps.toMutableList()
        for (dns in dnsServers) {
            if (result.any { it.contains(dns) }) continue
            val hostMask = if (dns is Inet6Address) 128 else 32
            result += InetNetwork.parse("${dns.hostAddress}/$hostMask")
        }
        return result
    }

    /** True when [network] covers [address]. */
    private fun InetNetwork.contains(address: InetAddress): Boolean {
        if (this.address.javaClass != address.javaClass) return false
        val net = this.address.address
        val addr = address.address
        var bitsLeft = this.mask
        for (i in net.indices) {
            if (bitsLeft <= 0) return true
            val take = minOf(8, bitsLeft)
            // Compare only the leading `take` bits of this byte.
            val maskByte = (0xFF shl (8 - take)) and 0xFF
            if ((net[i].toInt() and maskByte) != (addr[i].toInt() and maskByte)) return false
            bitsLeft -= take
        }
        return true
    }
}

/** Parse an IP literal without the DNS lookup [InetAddress.getByName] would do. */
internal object InetAddresses {
    fun parse(literal: String): InetAddress {
        // InetAddress.getByName only resolves when the argument is NOT a literal,
        // so this stays offline for the addresses we care about. Reject anything
        // that is not a literal so a typo can never turn into a DNS lookup on the
        // phone's normal network.
        require(literal.any { it == ':' } || literal.count { it == '.' } == 3) {
            "not an IP literal: $literal"
        }
        return InetAddress.getByName(literal)
    }
}
