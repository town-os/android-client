package com.townos.client.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.townos.client.api.Network
import com.townos.client.dns.PrivateDnsCheck
import com.wireguard.android.backend.Tunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen, driven through the same state the view model produces.
 *
 * These are not pixel checks. The point is that the *warnings actually appear*:
 * the Private DNS alert is the app's only defence against its most confusing
 * failure mode, and a refactor that quietly stopped rendering it would otherwise
 * go unnoticed until a user was stuck with a connected tunnel and no working
 * names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TownOsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(
        state: UiState,
        onConnect: () -> Unit = {},
        onEnroll: (Network, String) -> Unit = { _, _ -> },
        onOpenPrivateDnsSettings: () -> Unit = {},
    ) {
        compose.setContent {
            TownOsScreen(
                state = state,
                onLogin = { _, _, _ -> },
                onEnroll = onEnroll,
                onConnect = onConnect,
                onDisconnect = {},
                onDnsOverride = {},
                onEndpointOverride = {},
                onVerifyDane = {},
                onForget = {},
                onDismiss = {},
                onOpenPrivateDnsSettings = onOpenPrivateDnsSettings,
            )
        }
    }

    private val enrolled = UiState(
        enrolled = true,
        selectedNetwork = "fart",
        tld = "fart",
        dnsServer = "192.168.122.50",
    )

    /** Count matching nodes without asserting — for "must NOT be present". */
    private fun ComposeContentTestRule.countWithText(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    // ---- the Private DNS alert ---------------------------------------------

    @Test
    fun `strict Private DNS raises the alert and names the provider`() {
        screen(enrolled.copy(privateDns = PrivateDnsCheck.Status.Strict("dns.google")))

        compose.onNodeWithText("Private DNS is blocking Town OS names").assertExists()
        assertTrue(compose.countWithText("dns.google") > 0)
    }

    @Test
    fun `the alert offers a route to the setting, and tapping it opens Settings`() {
        var opened = false
        screen(
            enrolled.copy(privateDns = PrivateDnsCheck.Status.Strict("dns.google")),
            onOpenPrivateDnsSettings = { opened = true },
        )

        compose.onNodeWithText("Open Private DNS settings").performScrollTo().performClick()

        assertTrue("tapping the alert button must open Private DNS settings", opened)
    }

    @Test
    fun `the alert is NOT dismissible`() {
        // Deliberate: while strict Private DNS is on, nothing under the TLD
        // resolves. Letting the user swipe the warning away would leave them with
        // a broken app and no explanation for it.
        screen(enrolled.copy(privateDns = PrivateDnsCheck.Status.Strict("dns.google")))

        assertEquals(
            "the Private DNS alert must have no Dismiss button",
            0,
            compose.countWithText("Dismiss"),
        )
    }

    @Test
    fun `no alert when Private DNS is off or opportunistic`() {
        // A false alarm is nearly as damaging as a missing one: it would tell the
        // user to change a setting that is working perfectly well.
        screen(enrolled.copy(privateDns = PrivateDnsCheck.Status.Ok))

        assertEquals(0, compose.countWithText("Private DNS is blocking Town OS names"))
    }

    // ---- enrollment and tunnel ---------------------------------------------

    @Test
    fun `a fresh install shows the login form`() {
        screen(UiState())

        compose.onNodeWithText("Connect to a box").assertExists()
        compose.onNodeWithText("Log in").assertExists()
    }

    @Test
    fun `available networks are offered with their TLD`() {
        screen(
            UiState(
                networks = listOf(
                    Network(name = "fart", tld = "fart", subnet = "10.90.12.0/24", running = true),
                ),
            ),
        )

        compose.onNodeWithText("Join a network").assertExists()
        assertTrue(compose.countWithText(".fart") > 0)
    }

    @Test
    fun `joining a network enrolls onto it`() {
        var joined: Network? = null
        screen(
            UiState(networks = listOf(Network(name = "fart", tld = "fart"))),
            onEnroll = { n, _ -> joined = n },
        )

        compose.onNodeWithText("Join fart").performScrollTo().performClick()

        assertEquals("fart", joined?.name)
    }

    @Test
    fun `an enrolled device shows Connect while the tunnel is down`() {
        screen(enrolled.copy(tunnelState = Tunnel.State.DOWN))

        compose.onNodeWithText("Disconnected").assertExists()
        compose.onNodeWithText("Connect").assertExists()
    }

    @Test
    fun `tapping Connect brings the tunnel up`() {
        var connected = false
        screen(enrolled.copy(tunnelState = Tunnel.State.DOWN), onConnect = { connected = true })

        compose.onNodeWithText("Connect").performScrollTo().performClick()

        assertTrue(connected)
    }

    @Test
    fun `a connected device shows its network and TLD`() {
        screen(enrolled.copy(tunnelState = Tunnel.State.UP))

        compose.onNodeWithText("Connected to fart").assertExists()
        compose.onNodeWithText("Disconnect").assertExists()
        assertTrue(compose.countWithText("Names resolve under .fart") > 0)
    }

    @Test
    fun `the resolver in use is shown, since it is the thing users must get right`() {
        screen(enrolled.copy(tunnelState = Tunnel.State.UP))

        assertTrue(compose.countWithText("192.168.122.50") > 0)
    }

    @Test
    fun `errors are surfaced rather than swallowed`() {
        screen(UiState(error = "That account is not an admin."))

        compose.onNodeWithText("That account is not an admin.").assertExists()
    }
}
