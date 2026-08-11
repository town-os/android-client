package com.townos.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.townos.client.api.Network
import com.townos.client.dns.PrivateDnsCheck
import com.wireguard.android.backend.Tunnel

@Composable
fun TownOsScreen(
    state: UiState,
    onLogin: (String, String, String) -> Unit,
    onEnroll: (Network, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDnsOverride: (String) -> Unit,
    onEndpointOverride: (String) -> Unit,
    onVerifyDane: (String) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPrivateDnsSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The window is edge-to-edge (Android 15 forces it at targetSdk 35),
            // so `adjustResize` no longer shrinks it for the keyboard — the IME
            // arrives as an inset that something has to consume, or it simply
            // paints over the bottom of the screen. safeDrawing covers the system
            // bars, the cutout, AND the IME.
            //
            // Order matters: this sits BEFORE verticalScroll so it shrinks the
            // scroll *viewport*. Below verticalScroll it would only pad the
            // content, leaving the viewport full-height and the fields under the
            // keyboard still unreachable. Shrinking the viewport is what gives the
            // scroll the extra range — and what lets a focused field scroll itself
            // back into view.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Town OS", style = MaterialTheme.typography.headlineMedium)

        if (state.busy) CircularProgressIndicator()

        // Deliberately first, and deliberately NOT dismissible: while this is on,
        // nothing under the network's TLD resolves, and no amount of retrying in
        // the app will change that. Only the user can fix it.
        (state.privateDns as? PrivateDnsCheck.Status.Strict)?.let {
            PrivateDnsAlertCard(it, onOpenPrivateDnsSettings)
        }

        state.error?.let { Notice("Error", it, onDismiss) }
        state.message?.let { Notice("", it, onDismiss) }

        if (!state.enrolled) {
            LoginCard(state, onLogin)
            if (state.networks.isNotEmpty()) NetworkCard(state.networks, onEnroll)
        } else {
            TunnelCard(state, onConnect, onDisconnect, onForget)
            DnsCard(state, onDnsOverride, onEndpointOverride, onVerifyDane)
        }
    }
}

/**
 * The Private DNS alert.
 *
 * Styled as an error and given no dismiss button on purpose. Strict Private DNS
 * sends every lookup to a public DoT resolver, which has never heard of
 * `<pkg>.<repo>.<tld>` — so the tunnel connects, traffic flows, and every name
 * fails. It is the app's most confusing failure mode and the app cannot work
 * around it, so the only useful thing to do is say exactly what is wrong and put
 * the fix one tap away.
 */
@Composable
private fun PrivateDnsAlertCard(
    status: PrivateDnsCheck.Status.Strict,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Private DNS is blocking Town OS names", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android is sending every DNS lookup to \"${status.hostname}\" instead of to your " +
                    "box. Names like gitea.default.home will not resolve, even while the tunnel " +
                    "is connected.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Set Private DNS to Automatic or Off. (Automatic is fine — it only upgrades the " +
                    "current network's own resolvers.)",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Private DNS settings")
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun LoginCard(state: UiState, onLogin: (String, String, String) -> Unit) {
    var address by remember { mutableStateOf(state.boxAddress) }
    var username by remember { mutableStateOf(state.username) }
    var password by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to a box", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Box address") },
                supportingText = { Text("e.g. 192.168.122.50 — port 5309 is assumed") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Admin username") },
                supportingText = { Text("Enrolling a device requires an admin account") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onLogin(address, username, password) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log in") }
        }
    }
}

@Composable
private fun NetworkCard(networks: List<Network>, onEnroll: (Network, String) -> Unit) {
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL ?: "phone") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Join a network", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your device generates its own key — only the public half is sent to the box.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Device name") },
                modifier = Modifier.fillMaxWidth(),
            )
            networks.forEach { network ->
                Divider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(network.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        ".${network.tld} · ${network.subnet} · ${network.peerCount} peers" +
                            if (!network.running) " · not running" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { onEnroll(network, deviceName) }) { Text("Join ${network.name}") }
                }
            }
        }
    }
}

@Composable
private fun TunnelCard(
    state: UiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val up = state.tunnelState == Tunnel.State.UP
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (up) "Connected to ${state.selectedNetwork}" else "Disconnected",
                style = MaterialTheme.typography.titleMedium,
            )
            state.tld?.let { Text("Names resolve under .$it", style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = if (up) onDisconnect else onConnect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (up) "Disconnect" else "Connect") }
            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Forget this network")
            }
        }
    }
}

@Composable
private fun DnsCard(
    state: UiState,
    onDnsOverride: (String) -> Unit,
    onEndpointOverride: (String) -> Unit,
    onVerifyDane: (String) -> Unit,
) {
    var dns by remember { mutableStateOf(state.dnsServer.orEmpty()) }
    var endpoint by remember { mutableStateOf("") }
    var daneHost by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DNS", style = MaterialTheme.typography.titleMedium)
            Text(
                "Queries are routed into the tunnel so the box sees your overlay address and " +
                    "answers with this network's view. If the box's resolver is not bound to the " +
                    "overlay address, point this at the box's LAN address instead — it will still " +
                    "be routed through the tunnel.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = dns,
                onValueChange = { dns = it; onDnsOverride(it) },
                label = { Text("Resolver address") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it; onEndpointOverride(it) },
                label = { Text("Endpoint override (host:port)") },
                supportingText = { Text("Only needed if the box advertised an unreachable address") },
                modifier = Modifier.fillMaxWidth(),
            )

            Divider()
            Text("Check a certificate pin (DANE)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Looks up the TLSA record the box publishes for a package and shows the pin.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = daneHost,
                onValueChange = { daneHost = it },
                label = { Text("Package FQDN") },
                supportingText = { Text("e.g. gitea.default.${state.tld ?: "home"}") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onVerifyDane(daneHost) },
                enabled = daneHost.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Look up TLSA") }

            state.caFingerprint?.let {
                Divider()
                Text("Box CA (SHA-256)", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
