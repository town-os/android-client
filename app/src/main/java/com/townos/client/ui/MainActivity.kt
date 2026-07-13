package com.townos.client.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.townos.client.dns.PrivateDnsAlert

class MainActivity : ComponentActivity() {

    private val model: AppViewModel by viewModels()

    /**
     * Android requires an explicit user grant before any app may open a VPN
     * interface. VpnService.prepare() returns an Intent the first time (and
     * after the user revokes it); null means we already hold the grant.
     */
    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) model.connect()
    }

    /**
     * The Private DNS warning is most useful as a *notification*, because the
     * user hits the failure in a browser, not while looking at this app. From
     * API 33 that needs a runtime grant. Asked for once, at launch; denial is
     * fine — the in-app alert still fires.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not, the in-app alert covers us */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PrivateDnsAlert.canNotify(this)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface {
                    val state by model.state.collectAsState()
                    TownOsScreen(
                        state = state,
                        onLogin = model::login,
                        onEnroll = model::enroll,
                        onConnect = ::requestVpnThenConnect,
                        onDisconnect = model::disconnect,
                        onDnsOverride = model::setDnsOverride,
                        onEndpointOverride = model::setEndpointOverride,
                        onVerifyDane = model::verifyDane,
                        onForget = model::forget,
                        onDismiss = model::dismissError,
                        onOpenPrivateDnsSettings = model::openPrivateDnsSettings,
                    )
                }
            }
        }
    }

    private fun requestVpnThenConnect() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent == null) {
            model.connect()
        } else {
            vpnPermission.launch(intent)
        }
    }
}
