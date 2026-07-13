package com.townos.client.net

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust for a Town OS box.
 *
 * Every package on a box is served over HTTPS by the shared :443 ingress with a
 * leaf issued by the box's own CA (src/tls/ca.go). No Android trust store knows
 * that CA, so without help every request to `<pkg>.<repo>.<tld>` fails with
 * "certificate signed by unknown authority".
 *
 * We add the box's CA as an *extra* trust anchor rather than replacing the
 * system anchors: the app also talks to ordinary internet hosts, and a
 * box-only trust store would break those. Adding it here (in OkHttp) rather
 * than in network_security_config keeps the anchor scoped to connections this
 * app makes deliberately.
 *
 * The CA is fetched, unauthenticated, from `GET /tls/ca.crt`.
 *
 * A caveat worth stating plainly: on first contact we have no way to know the
 * CA we just downloaded is the right one — this is trust-on-first-use. Pin it
 * (persist the fingerprint) and treat a *change* as an error, which is what
 * [fingerprint] is for. DANE (see [com.townos.client.dns.DaneVerifier]) does not
 * fix this either: Town OS publishes no DNSSEC chain for its private TLD, so a
 * TLSA record is only as trustworthy as the resolver that served it.
 */
class TownOsTrust private constructor(
    private val trustManager: X509TrustManager?,
    val caCertificate: X509Certificate?,
) {

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val tm = trustManager ?: return builder
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tm), null)
        }
        return builder.sslSocketFactory(context.socketFactory, tm)
    }

    /** SHA-256 of the CA certificate, for pinning and for showing the user. */
    fun fingerprint(): String? = caCertificate?.let { Fingerprints.sha256(it.encoded) }

    companion object {
        /** Trust only the system anchors — used before we have fetched a CA. */
        fun systemOnly(): TownOsTrust = TownOsTrust(null, null)

        /**
         * System anchors plus the box's CA.
         *
         * Both sets are loaded into one KeyStore and handed to a single
         * TrustManagerFactory, so a chain validates if it terminates at *either*
         * the box CA or a public root.
         */
        fun withBoxCa(caPem: ByteArray): TownOsTrust {
            val ca = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(caPem)) as X509Certificate

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                // Seed with the platform's default anchors...
                val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                system.init(null as KeyStore?)
                system.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .flatMap { it.acceptedIssuers.asIterable() }
                    .forEachIndexed { i, cert -> setCertificateEntry("system-$i", cert) }
                // ...then add the box's.
                setCertificateEntry("town-os-ca", ca)
            }

            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            val tm = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: error("no X509TrustManager available")

            return TownOsTrust(tm, ca)
        }
    }
}

internal object Fingerprints {
    fun sha256(der: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString(":") { "%02X".format(it) }
}
