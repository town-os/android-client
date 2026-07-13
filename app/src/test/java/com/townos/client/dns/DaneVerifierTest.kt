package com.townos.client.dns

import com.townos.client.TestCerts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin-matching rules. A false "Match" here means the app accepts a
 * certificate the box never vouched for, so the negative cases matter at least
 * as much as the positive one.
 */
class DaneVerifierTest {

    @Test
    fun `leaf matching the published SPKI pin verifies`() {
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        val pin = DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(leaf))

        assertEquals(DaneVerifier.Result.Match, DaneVerifier.match(listOf(pin), leaf))
    }

    @Test
    fun `a different key is a mismatch, not a match`() {
        // The pin was published for one cert; the server presents another. This
        // is the attack the pin exists to catch.
        val published = TestCerts.selfSigned("gitea.default.fart")
        val impostor = TestCerts.selfSigned("gitea.default.fart")
        val pin = DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(published))

        val result = DaneVerifier.match(listOf(pin), impostor)

        assertTrue("expected Mismatch, got $result", result is DaneVerifier.Result.Mismatch)
    }

    @Test
    fun `no records means NoPins, which is not a failure`() {
        // An unpublished service (or one excluded via dns_excluded_services) has
        // no TLSA record. The caller must be able to tell that apart from a
        // failed verification and apply its own policy.
        val leaf = TestCerts.selfSigned("gitea.default.fart")

        assertEquals(DaneVerifier.Result.NoPins, DaneVerifier.match(emptyList(), leaf))
    }

    @Test
    fun `pins we do not understand are reported, never silently accepted`() {
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        // Usage 2 (DANE-TA) / selector 0 (full cert) — valid DANE, but not what
        // Town OS emits and not something we implement. It must not be treated
        // as a match just because a record exists.
        val pin = DaneVerifier.Pin(2, 0, 1, TestCerts.spkiSha256(leaf))

        val result = DaneVerifier.match(listOf(pin), leaf)

        assertTrue("expected UnsupportedPins, got $result", result is DaneVerifier.Result.UnsupportedPins)
    }

    @Test
    fun `a usable pin alongside an unusable one still verifies`() {
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        val unusable = DaneVerifier.Pin(2, 0, 1, byteArrayOf(1, 2, 3))
        val usable = DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(leaf))

        assertEquals(DaneVerifier.Result.Match, DaneVerifier.match(listOf(unusable, usable), leaf))
    }

    @Test
    fun `parses the RDATA form Town OS writes`() {
        // Exactly what controller_tls.go emits: "3 1 1 " + hex(sha256(spki)).
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        val hex = TestCerts.spkiSha256(leaf).joinToString("") { "%02x".format(it) }

        val pin = DaneVerifier.parsePin("3 1 1 $hex")!!

        assertEquals(3, pin.usage)
        assertEquals(1, pin.selector)
        assertEquals(1, pin.matchingType)
        assertTrue(pin.isDaneEeSpkiSha256)
        assertEquals(DaneVerifier.Result.Match, DaneVerifier.match(listOf(pin), leaf))
    }

    @Test
    fun `malformed RDATA parses to null rather than a bogus pin`() {
        assertNull(DaneVerifier.parsePin("3 1 1"))
        assertNull(DaneVerifier.parsePin("3 1 1 nothex"))
        assertNull(DaneVerifier.parsePin("x 1 1 ab"))
        assertNull(DaneVerifier.parsePin("3 1 1 abc")) // odd-length hex
        assertNull(DaneVerifier.parsePin(""))
        assertNull(DaneVerifier.parsePin("3 1 1 ab extra"))
        assertNull(DaneVerifier.parsePin("3 1 1 ")) // empty association data
    }

    @Test
    fun `RDATA with extra whitespace still parses`() {
        // Presentation format is whitespace-tolerant; a resolver may hand back
        // the RDATA with tabs or runs of spaces.
        val pin = DaneVerifier.parsePin("  3   1  1\tabcd  ")!!
        assertEquals(3, pin.usage)
        assertArrayEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte()), pin.data)
    }

    @Test
    fun `a truncated digest does not match a full one`() {
        // A prefix-comparison bug would accept a pin that only matches the first
        // few bytes. contentEquals must compare length too.
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        val truncated = TestCerts.spkiSha256(leaf).copyOfRange(0, 16)
        val pin = DaneVerifier.Pin(3, 1, 1, truncated)

        assertTrue(DaneVerifier.match(listOf(pin), leaf) is DaneVerifier.Result.Mismatch)
    }

    @Test
    fun `usage 3 selector 0 - full cert rather than SPKI - is unsupported, not a match`() {
        // Selector 0 pins the whole certificate, so the association data is a
        // digest of the DER cert, NOT of the SPKI. Comparing it against an SPKI
        // digest would be comparing unrelated bytes.
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        val pin = DaneVerifier.Pin(3, 0, 1, TestCerts.spkiSha256(leaf))

        assertTrue(DaneVerifier.match(listOf(pin), leaf) is DaneVerifier.Result.UnsupportedPins)
    }

    @Test
    fun `matching type 2 - SHA-512 - is unsupported, not a match`() {
        val leaf = TestCerts.selfSigned("gitea.default.fart")
        val pin = DaneVerifier.Pin(3, 1, 2, TestCerts.spkiSha256(leaf))

        assertTrue(DaneVerifier.match(listOf(pin), leaf) is DaneVerifier.Result.UnsupportedPins)
    }

    @Test
    fun `a mismatch reports both the expected and actual digests`() {
        // The user needs to be able to tell "wrong cert" from "stale record".
        val published = TestCerts.selfSigned("a")
        val presented = TestCerts.selfSigned("b")
        val pin = DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(published))

        val result = DaneVerifier.match(listOf(pin), presented) as DaneVerifier.Result.Mismatch

        assertEquals(1, result.expected.size)
        assertEquals(TestCerts.spkiSha256(published).toHexString(), result.expected[0])
        assertEquals(TestCerts.spkiSha256(presented).toHexString(), result.actual)
    }

    @Test
    fun `several published pins - matching any one is enough`() {
        // A key rollover publishes the old and new pins side by side; either must
        // verify.
        val old = TestCerts.selfSigned("old")
        val new = TestCerts.selfSigned("new")
        val pins = listOf(
            DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(old)),
            DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(new)),
        )

        assertEquals(DaneVerifier.Result.Match, DaneVerifier.match(pins, old))
        assertEquals(DaneVerifier.Result.Match, DaneVerifier.match(pins, new))
    }

    @Test
    fun `the same keypair in a different certificate still matches`() {
        // Selector 1 pins the SubjectPublicKeyInfo, not the certificate. Reissuing
        // a leaf with the same key (a renewal) must NOT invalidate the pin.
        val keys = TestCerts.keyPair()
        val original = TestCerts.selfSigned("gitea.default.fart", keys)
        val renewed = TestCerts.selfSigned("gitea.default.fart", keys)
        val pin = DaneVerifier.Pin(3, 1, 1, TestCerts.spkiSha256(original))

        assertEquals(DaneVerifier.Result.Match, DaneVerifier.match(listOf(pin), renewed))
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
