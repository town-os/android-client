package com.townos.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.townos.client.api.Network
import com.townos.client.api.TownOsApi
import com.townos.client.data.Store
import com.townos.client.dns.DaneVerifier
import com.townos.client.dns.PrivateDnsAlert
import com.townos.client.dns.PrivateDnsCheck
import com.townos.client.net.TownOsTrust
import com.townos.client.vpn.TunnelConfigBuilder
import com.townos.client.vpn.TunnelController
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

data class UiState(
    val boxAddress: String = "",
    val username: String = "",
    val networks: List<Network> = emptyList(),
    val selectedNetwork: String? = null,
    val enrolled: Boolean = false,
    val tunnelState: Tunnel.State = Tunnel.State.DOWN,
    val tld: String? = null,
    val dnsServer: String? = null,
    val caFingerprint: String? = null,
    /**
     * Strict Private DNS, the one Android setting that silently breaks name
     * resolution through the tunnel. Non-Ok means the user must fix it; nothing
     * the app does can work around it.
     */
    val privateDns: PrivateDnsCheck.Status = PrivateDnsCheck.Status.Ok,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val privateDnsBroken: Boolean get() = privateDns is PrivateDnsCheck.Status.Strict
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store.encrypted(app)
    private val tunnel = TunnelController(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        tunnel.listener = { newState ->
            _state.update { it.copy(tunnelState = newState) }
            syncPrivateDnsNotification()
        }

        // Watch Private DNS for the life of the app rather than sampling at
        // connect time: the user can flip it while connected (indeed, we are
        // about to ask them to), and the warning should clear itself the instant
        // they do.
        viewModelScope.launch {
            PrivateDnsCheck.statusFlow(app).collect { status ->
                _state.update { it.copy(privateDns = status) }
                syncPrivateDnsNotification()
            }
        }
        _state.update {
            it.copy(
                boxAddress = store.boxAddress.orEmpty(),
                enrolled = store.enrolled,
                tunnelState = tunnel.state,
                tld = store.tld,
                dnsServer = effectiveDnsServer(),
                caFingerprint = store.caFingerprint,
                selectedNetwork = store.networkName,
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null, message = null) }

    /**
     * Log in and fetch what we need to enroll: the networks, and the box's CA.
     *
     * Both /networks and /networks/peers/add are admin-only, so this needs admin
     * credentials — Town OS has no device-enrollment or invite flow.
     */
    fun login(address: String, username: String, password: String) = run {
        val url = TownOsApi.parseBaseUrl(address)
        if (url == null) {
            _state.update { it.copy(error = "Not a valid box address: $address") }
            return@run
        }
        launchBusy {
            val api = TownOsApi(url, TownOsTrust.systemOnly())
            val auth = withContext(Dispatchers.IO) { api.authenticate(username, password) }

            store.boxAddress = address
            store.token = auth.token

            // Fetch and pin the CA. It is unauthenticated and public, but a
            // *change* in it after first contact is worth surfacing.
            val caPem = withContext(Dispatchers.IO) { runCatching { api.fetchCaPem() }.getOrNull() }
            var fingerprint = store.caFingerprint
            var caWarning: String? = null
            if (caPem != null) {
                val trust = TownOsTrust.withBoxCa(caPem)
                val newFingerprint = trust.fingerprint()
                if (fingerprint != null && newFingerprint != fingerprint) {
                    caWarning = "The box's CA certificate has CHANGED since you last connected. " +
                        "That is expected after a factory reset — otherwise, treat it as a warning."
                }
                store.caPem = String(caPem)
                store.caFingerprint = newFingerprint
                fingerprint = newFingerprint
            }

            val networks = withContext(Dispatchers.IO) { api.listNetworks(auth.token) }
                .filter { it.joinable }

            _state.update {
                it.copy(
                    boxAddress = address,
                    username = username,
                    networks = networks,
                    caFingerprint = fingerprint,
                    error = caWarning,
                    message = if (networks.isEmpty()) {
                        "No joinable networks. The default 'home' network is LAN-only — " +
                            "create a network in the Town OS UI first."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Enroll this device as a peer on [network].
     *
     * The keypair is generated here and only the public half is sent. If we let
     * the box generate it, it would return our private key in the HTTP response
     * — over plain HTTP, since the control API terminates no TLS.
     */
    fun enroll(network: Network, deviceName: String) = launchBusy {
        val url = TownOsApi.parseBaseUrl(store.boxAddress.orEmpty())
            ?: error("no box address")
        val token = store.token ?: error("not logged in")
        val api = TownOsApi(url, TownOsTrust.systemOnly())

        val keys = TunnelController.generateKeyPair()
        val response = withContext(Dispatchers.IO) {
            api.addPeer(token, network.name, deviceName, keys.publicKey.toBase64())
        }

        store.privateKey = keys.privateKey.toBase64()
        store.peerConfig = response.config
        store.networkName = network.name
        // Prefer the box's own answer for the TLD over the network record, since
        // GET /dns/tld is the operator-visible source of truth.
        store.tld = withContext(Dispatchers.IO) {
            runCatching { api.dnsStatus(token).tld }.getOrNull()?.takeIf { it.isNotBlank() }
        } ?: network.tld

        _state.update {
            it.copy(
                enrolled = true,
                selectedNetwork = network.name,
                tld = store.tld,
                dnsServer = effectiveDnsServer(),
                message = "Enrolled as ${response.peer?.allowedIp ?: "peer"} on ${network.name}.",
            )
        }
    }

    fun connect() = launchBusy {
        val config = buildConfig()
        withContext(Dispatchers.IO) { tunnel.up(config) }
        _state.update { it.copy(tunnelState = tunnel.state, dnsServer = effectiveDnsServer()) }
        // Re-read rather than trusting the flow's last value: the tunnel coming
        // up changes LinkProperties, and we want the notification to reflect the
        // post-connect reality immediately.
        _state.update { it.copy(privateDns = PrivateDnsCheck.status(getApplication())) }
        syncPrivateDnsNotification()
    }

    fun disconnect() = launchBusy {
        withContext(Dispatchers.IO) { tunnel.down() }
        _state.update { it.copy(tunnelState = tunnel.state) }
        syncPrivateDnsNotification()
    }

    /** Take the user to the Android setting; we cannot change it for them. */
    fun openPrivateDnsSettings() {
        PrivateDnsAlert.openSettings(getApplication())
    }

    /**
     * The notification exists for when the user is *not* looking at this app —
     * they hit the failure in a browser. So it is posted only while the tunnel is
     * up and Private DNS is strict, and cleared otherwise (including on
     * disconnect, when the setting stops mattering to us).
     */
    private fun syncPrivateDnsNotification() {
        val state = _state.value
        val relevant = state.tunnelState == Tunnel.State.UP && state.privateDnsBroken
        val context = getApplication<Application>()
        if (relevant) {
            PrivateDnsAlert.update(context, state.privateDns)
        } else {
            PrivateDnsAlert.clear(context)
        }
    }

    fun setDnsOverride(value: String) {
        store.dnsOverride = value.trim().ifBlank { null }
        _state.update { it.copy(dnsServer = effectiveDnsServer()) }
    }

    fun setEndpointOverride(value: String) {
        store.endpointOverride = value.trim().ifBlank { null }
    }

    fun forget() {
        store.clearEnrollment()
        _state.update { it.copy(enrolled = false, selectedNetwork = null, tld = null) }
    }

    /**
     * Check a package's certificate against the TLSA pin the box publishes in
     * DNS. Runs the query through the tunnel so we get the scoped view.
     */
    fun verifyDane(host: String) = launchBusy {
        val dns = effectiveDnsServer() ?: error("no DNS server configured")
        val result = withContext(Dispatchers.IO) {
            val verifier = DaneVerifier(InetAddress.getByName(dns))
            val pins = verifier.lookupPins(host, 443)
            when {
                pins.isEmpty() ->
                    "No TLSA records for $host. The service may be unpublished, or DNS is not " +
                        "reaching the box."
                else ->
                    "TLSA pins for $host:\n" + pins.joinToString("\n") { pin ->
                        "  ${pin.usage} ${pin.selector} ${pin.matchingType} " +
                            pin.data.joinToString("") { b -> "%02x".format(b) }.take(32) + "…"
                    }
            }
        }
        _state.update { it.copy(message = result) }
    }

    private fun buildConfig() = TunnelConfigBuilder.build(
        peerConfig = store.peerConfig ?: error("not enrolled"),
        privateKey = store.privateKey,
        searchDomain = store.tld,
        dnsServerOverride = store.dnsOverride,
        endpointOverride = store.endpointOverride,
    )

    /** What resolver we will actually install: the override, else the config's DNS line. */
    private fun effectiveDnsServer(): String? {
        store.dnsOverride?.takeIf { it.isNotBlank() }?.let { return it }
        val config = store.peerConfig ?: return null
        return config.lineSequence()
            .firstOrNull { it.trimStart().startsWith("DNS", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = humanize(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun humanize(e: Exception): String = when {
        e is TownOsApi.ApiException && e.unauthorized ->
            "Login rejected. Note the box wipes all sessions when it restarts, so a previously " +
                "working token stops working after a reboot."
        e is TownOsApi.ApiException && e.forbidden ->
            "That account is not an admin. Creating peers requires an admin account."
        else -> e.message ?: e.toString()
    }
}
