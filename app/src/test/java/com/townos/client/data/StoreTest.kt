package com.townos.client.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persisted enrollment state.
 *
 * The encryption itself (EncryptedSharedPreferences, keys in the Android
 * keystore) is the platform's job and is not the interesting failure mode. The
 * bookkeeping is: an enrollment that reports itself complete when only half of
 * it exists, or a "forget" that wipes the wrong things — leaving a private key
 * on disk for a network the user believes they left, or throwing away the box
 * address they would then have to retype.
 *
 * Store takes the SharedPreferences interface, so all of that is testable with
 * [FakeSharedPreferences] and no Android framework.
 */
class StoreTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: Store

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = Store(prefs)
    }

    @Test
    fun `a fresh store is not enrolled and holds nothing`() {
        assertFalse(store.enrolled)
        assertNull(store.boxAddress)
        assertNull(store.privateKey)
        assertNull(store.peerConfig)
        assertNull(store.tld)
    }

    @Test
    fun `values round-trip`() {
        store.boxAddress = "192.168.122.50"
        store.token = "jwt"
        store.privateKey = "PRIVKEY="
        store.peerConfig = "[Interface]"
        store.networkName = "fart"
        store.tld = "fart"
        store.dnsOverride = "192.168.122.50"
        store.endpointOverride = "192.168.122.50:51820"
        store.caPem = "-----BEGIN CERTIFICATE-----"
        store.caFingerprint = "AA:BB"

        assertEquals("192.168.122.50", store.boxAddress)
        assertEquals("jwt", store.token)
        assertEquals("PRIVKEY=", store.privateKey)
        assertEquals("[Interface]", store.peerConfig)
        assertEquals("fart", store.networkName)
        assertEquals("fart", store.tld)
        assertEquals("192.168.122.50", store.dnsOverride)
        assertEquals("192.168.122.50:51820", store.endpointOverride)
        assertEquals("-----BEGIN CERTIFICATE-----", store.caPem)
        assertEquals("AA:BB", store.caFingerprint)
    }

    @Test
    fun `enrolled requires BOTH the peer config and the private key`() {
        // Either half alone is useless: a config without our key cannot become a
        // tunnel (the box wrote a placeholder there), and a key without a config
        // has nothing to attach to. Reporting "enrolled" for a half state would
        // hand the user a Connect button that can only fail.
        store.peerConfig = "[Interface]"
        assertFalse(store.enrolled)

        store.peerConfig = null
        store.privateKey = "PRIVKEY="
        assertFalse(store.enrolled)

        store.peerConfig = "[Interface]"
        assertTrue(store.enrolled)
    }

    @Test
    fun `a blank peer config does not count as enrolled`() {
        store.privateKey = "PRIVKEY="
        store.peerConfig = "   "
        assertFalse(store.enrolled)
    }

    @Test
    fun `clearEnrollment wipes the key material and the network`() {
        store.privateKey = "PRIVKEY="
        store.peerConfig = "[Interface]"
        store.networkName = "fart"
        store.tld = "fart"

        store.clearEnrollment()

        // The private key above all: leaving it on disk for a network the user
        // believes they have left is the worst outcome available here.
        assertNull(store.privateKey)
        assertNull(store.peerConfig)
        assertNull(store.networkName)
        assertNull(store.tld)
        assertFalse(store.enrolled)
    }

    @Test
    fun `the private key is really gone from storage, not just from the getter`() {
        store.privateKey = "PRIVKEY="
        store.peerConfig = "[Interface]"

        store.clearEnrollment()

        assertFalse("the key must be removed from the backing store", prefs.contains("private_key"))
    }

    @Test
    fun `clearEnrollment keeps the box address and credentials so re-joining is one tap`() {
        store.boxAddress = "192.168.122.50"
        store.token = "jwt"
        store.caFingerprint = "AA:BB"
        store.privateKey = "PRIVKEY="
        store.peerConfig = "[Interface]"

        store.clearEnrollment()

        assertEquals("192.168.122.50", store.boxAddress)
        assertEquals("jwt", store.token)
        // The pinned CA must survive too: dropping it would silently return the
        // next connection to trust-on-first-use, losing our only means of
        // noticing that the box's CA has changed.
        assertEquals("AA:BB", store.caFingerprint)
    }

    @Test
    fun `overrides survive clearEnrollment`() {
        // The resolver override describes the BOX — where rolodex is actually
        // bound — not the enrollment. Wiping it would silently break DNS again on
        // the next join, which is the bug this whole app exists to avoid.
        store.dnsOverride = "192.168.122.50"
        store.endpointOverride = "192.168.122.50:51820"
        store.privateKey = "k"
        store.peerConfig = "[Interface]"

        store.clearEnrollment()

        assertEquals("192.168.122.50", store.dnsOverride)
        assertEquals("192.168.122.50:51820", store.endpointOverride)
    }

    @Test
    fun `state persists across Store instances on the same preferences`() {
        store.boxAddress = "192.168.122.50"
        store.privateKey = "k"
        store.peerConfig = "[Interface]"

        val reopened = Store(prefs)

        assertEquals("192.168.122.50", reopened.boxAddress)
        assertTrue(reopened.enrolled)
    }

    @Test
    fun `writing null clears a value`() {
        store.dnsOverride = "192.168.122.50"
        store.dnsOverride = null

        assertNull(store.dnsOverride)
    }
}
