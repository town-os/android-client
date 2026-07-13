package com.townos.client.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strict-vs-opportunistic rule.
 *
 * Getting this backwards is costly in both directions: a false Strict nags the
 * user to change a setting that is working fine, and a false Ok leaves them with
 * a connected tunnel where no name resolves and no explanation anywhere. The
 * distinguishing signal is subtle — opportunistic DoT reports *active* with a
 * null server name — so it is pinned here.
 */
class PrivateDnsCheckTest {

    @Test
    fun `off is fine`() {
        assertEquals(
            PrivateDnsCheck.Status.Ok,
            PrivateDnsCheck.classify(isPrivateDnsActive = false, privateDnsServerName = null),
        )
    }

    @Test
    fun `opportunistic is fine - active but no provider hostname`() {
        // Automatic mode: Android upgrades the CURRENT network's own resolvers to
        // DoT and falls back to plaintext to those same resolvers. Queries still
        // reach the box, so this must NOT warn.
        assertEquals(
            PrivateDnsCheck.Status.Ok,
            PrivateDnsCheck.classify(isPrivateDnsActive = true, privateDnsServerName = null),
        )
    }

    @Test
    fun `strict provider hostname is the broken case`() {
        val status = PrivateDnsCheck.classify(
            isPrivateDnsActive = true,
            privateDnsServerName = "dns.google",
        )

        assertTrue(status is PrivateDnsCheck.Status.Strict)
        assertEquals("dns.google", (status as PrivateDnsCheck.Status.Strict).hostname)
    }

    @Test
    fun `a blank hostname is not strict`() {
        // Defensive: an empty string is not a provider, and reporting Strict("")
        // would produce a nonsense warning naming no server.
        assertEquals(
            PrivateDnsCheck.Status.Ok,
            PrivateDnsCheck.classify(isPrivateDnsActive = true, privateDnsServerName = "   "),
        )
    }

    @Test
    fun `a hostname without the active flag is not strict`() {
        // Private DNS configured but not currently in effect on this network.
        assertEquals(
            PrivateDnsCheck.Status.Ok,
            PrivateDnsCheck.classify(isPrivateDnsActive = false, privateDnsServerName = "dns.google"),
        )
    }
}
