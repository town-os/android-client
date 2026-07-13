package com.townos.client.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the Town OS control API (systemcontroller, :5309).
 *
 * These mirror the Go structs one-for-one; the names below are the `json` tags,
 * not the Go field names. Keep them in sync with:
 *   src/svc/systemcontroller/controller_networks.go
 *   src/svc/systemcontroller/controller_auth.go
 *   src/account/network.go
 */

@Serializable
data class AuthenticateRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthenticateResponse(
    val token: String,
    val account: Account? = null,
)

@Serializable
data class Account(
    val username: String,
    val admin: Boolean = false,
)

/**
 * GET /networks. Note the Go type embeds account.Network, so this arrives flat —
 * there is no nested "network" object.
 */
@Serializable
data class Network(
    val name: String,
    val tld: String,
    val subnet: String = "",
    val address: String = "",
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("listen_port") val listenPort: Int = 0,
    val enabled: Boolean = false,
    @SerialName("peer_count") val peerCount: Int = 0,
    val `interface`: String = "",
    val running: Boolean = false,
) {
    /**
     * The default network is LAN-only: applyNetworkTransport() returns early for
     * it, so it has no WireGuard interface, no overlay subnet and no peers.
     * Adding a peer to it "succeeds" — a row is written and a config string is
     * returned — but nothing will ever be listening. Filter it out of anything
     * the user can enroll into.
     */
    val joinable: Boolean get() = name != DEFAULT_NETWORK_NAME

    companion object {
        const val DEFAULT_NETWORK_NAME = "home"
    }
}

@Serializable
data class AddPeerRequest(
    val network: String,
    val name: String,
    /**
     * Our locally generated public key. Sending it is what keeps the private key
     * off the wire: when this is empty the box generates the keypair itself and
     * returns the *private* key in the response, over plain HTTP.
     */
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class AddPeerResponse(
    val peer: NetworkPeer? = null,
    /** Only set when the box generated the keypair — i.e. we did not send a public key. */
    @SerialName("private_key") val privateKey: String? = null,
    /** A complete wg-quick config. See TunnelConfigBuilder for what we do with it. */
    val config: String,
)

@Serializable
data class NetworkPeer(
    val network: String = "",
    @SerialName("public_key") val publicKey: String = "",
    val name: String = "",
    @SerialName("allowed_ip") val allowedIp: String = "",
    val endpoint: String = "",
)

@Serializable
data class RemovePeerRequest(
    val network: String,
    @SerialName("public_key") val publicKey: String,
)

/** GET /dns/status. */
@Serializable
data class DnsStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val tld: String = "",
    @SerialName("record_count") val recordCount: Int = 0,
)
