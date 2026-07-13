package com.townos.client.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Android's "Private DNS" (DNS-over-TLS), which silently defeats the whole point
 * of this app.
 *
 * When Private DNS is set to a **provider hostname** (Settings > Network >
 * Private DNS), Android sends *every* lookup to that DoT server — including
 * lookups that would otherwise go to the DNS servers a VpnService installed. A
 * Town OS box publishes its names only in its own resolver, so
 * `<pkg>.<repo>.<tld>` comes back NXDOMAIN from Cloudflare or Google while the
 * tunnel sits there looking perfectly healthy. It is the single most confusing
 * way this app can fail, and an app cannot override the setting — only the user
 * can, so we have to tell them.
 *
 * Three modes, only one of which is a problem:
 *
 *  - **Off** — fine.
 *  - **Automatic (opportunistic)** — fine. Android probes the *current network's*
 *    own DNS servers for DoT support and falls back to plaintext to those same
 *    servers. Queries still reach the box.
 *  - **Provider hostname (strict)** — broken, always.
 *
 * There is no public API for the mode, but the pair
 * (`isPrivateDnsActive`, `privateDnsServerName`) distinguishes them:
 * `privateDnsServerName` is non-null **only** in strict mode, while
 * opportunistic DoT reports active with a null name. Requires ACCESS_NETWORK_STATE.
 */
object PrivateDnsCheck {

    sealed interface Status {
        /** Private DNS is off, or opportunistic — either way, we resolve fine. */
        data object Ok : Status

        /** Strict DoT to [hostname]. Every lookup bypasses the box. */
        data class Strict(val hostname: String) : Status
    }

    /**
     * Classify [props] directly. Split out from the ConnectivityManager lookup so
     * the rule — which is subtle and easy to get backwards — is testable.
     */
    fun classify(isPrivateDnsActive: Boolean, privateDnsServerName: String?): Status =
        if (isPrivateDnsActive && !privateDnsServerName.isNullOrBlank()) {
            Status.Strict(privateDnsServerName)
        } else {
            Status.Ok
        }

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Status.Ok
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Status.Ok
        val network = cm.activeNetwork ?: return Status.Ok
        val props = cm.getLinkProperties(network) ?: return Status.Ok
        return classify(props.isPrivateDnsActive, props.privateDnsServerName)
    }

    /**
     * Emits the current status and every subsequent change.
     *
     * Toggling Private DNS does not restart the app, and the user may well flip
     * it *while* we are connected — that is the whole point of pointing them at
     * the setting. So we watch LinkProperties rather than sampling once, and the
     * warning clears itself the moment they fix it.
     */
    fun statusFlow(context: Context): Flow<Status> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            trySend(Status.Ok)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                trySend(classify(props.isPrivateDnsActive, props.privateDnsServerName))
            }

            override fun onLost(network: Network) {
                trySend(status(context))
            }
        }

        trySend(status(context))
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
