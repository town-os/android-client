package com.townos.client.vpn

import android.content.Context
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * Brings the WireGuard tunnel up and down.
 *
 * [GoBackend] ships its own VpnService (declared in the tunnel AAR) and applies
 * the [Config] to it — addresses, routes from AllowedIPs, and crucially the DNS
 * servers and search domains from the [com.wireguard.config.Interface]. That is
 * why all the DNS decisions live in [TunnelConfigBuilder]: by the time the
 * config reaches the backend, "wiring DNS" is just "hand it a config whose DNS
 * servers are reachable through the tunnel".
 */
class TunnelController(context: Context) {

    private val backend: Backend = GoBackend(context.applicationContext)

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            listener?.invoke(newState)
        }
    }

    var listener: ((Tunnel.State) -> Unit)? = null

    val state: Tunnel.State
        get() = backend.getState(tunnel)

    /** Bytes in/out, or null when the tunnel is down. */
    fun statistics(): Pair<Long, Long>? = runCatching {
        val stats = backend.getStatistics(tunnel)
        stats.totalRx() to stats.totalTx()
    }.getOrNull()

    fun up(config: Config) {
        backend.setState(tunnel, Tunnel.State.UP, config)
    }

    fun down() {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
    }

    companion object {
        const val TUNNEL_NAME = "townos"

        /**
         * Generate a keypair on the device.
         *
         * The private key must never leave the phone. If we enroll without
         * sending a public key, the box generates the pair itself and returns
         * our *private* key in the HTTP response — over plain HTTP, since the
         * control API terminates no TLS. So we always generate here and send
         * only the public half.
         */
        fun generateKeyPair(): KeyPair = KeyPair()

        fun publicKeyOf(privateKeyBase64: String): String =
            KeyPair(Key.fromBase64(privateKeyBase64)).publicKey.toBase64()
    }
}
