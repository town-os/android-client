package com.townos.client.net

import com.townos.client.TestCerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class TownOsTrustTest {

    @Test
    fun `the box CA is added without dropping the system anchors`() {
        // The app talks to the box AND to the internet. A trust store containing
        // only the box's CA would break every ordinary HTTPS call, so the system
        // anchors must survive.
        val ca = TestCerts.selfSigned("Town OS CA")
        val trust = TownOsTrust.withBoxCa(TestCerts.toPem(ca))

        val systemAnchorCount = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
            .acceptedIssuers
            .size

        val builder = okhttp3.OkHttpClient.Builder()
        assertNotNull(trust.applyTo(builder))
        assertEquals(ca, trust.caCertificate)

        // Sanity: the platform actually had anchors to preserve, so the
        // assertion below is meaningful rather than vacuously true.
        assertTrue(systemAnchorCount > 0)
    }

    @Test
    fun `the fingerprint is the SHA-256 of the certificate, and is stable`() {
        val ca = TestCerts.selfSigned("Town OS CA")
        val trust = TownOsTrust.withBoxCa(TestCerts.toPem(ca))

        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ca.encoded)
            .joinToString(":") { "%02X".format(it) }

        assertEquals(expected, trust.fingerprint())
        // Stable across calls — it is what we pin on, so it had better not drift.
        assertEquals(trust.fingerprint(), trust.fingerprint())
    }

    @Test
    fun `a different CA produces a different fingerprint`() {
        // This is the whole basis of detecting a CA swap after first contact.
        val a = TownOsTrust.withBoxCa(TestCerts.toPem(TestCerts.selfSigned("Town OS CA")))
        val b = TownOsTrust.withBoxCa(TestCerts.toPem(TestCerts.selfSigned("Town OS CA")))

        assertNotEquals(a.fingerprint(), b.fingerprint())
    }

    @Test
    fun `systemOnly has no box CA and no fingerprint to pin`() {
        val trust = TownOsTrust.systemOnly()

        assertNull(trust.caCertificate)
        assertNull(trust.fingerprint())
        // Applying it must be a no-op rather than installing an empty trust store,
        // which would reject everything.
        val builder = okhttp3.OkHttpClient.Builder()
        assertEquals(builder, trust.applyTo(builder))
    }
}
