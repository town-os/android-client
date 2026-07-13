package com.townos.client

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Mint real X.509 certificates for tests.
 *
 * The DANE and trust-anchor code both operate on actual certificates — SPKI
 * digests, DER encodings, chain validation — so testing them against synthetic
 * byte arrays would prove nothing. These helpers produce the real thing.
 */
object TestCerts {

    fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** A self-signed certificate, standing in for the box's local CA or a leaf. */
    fun selfSigned(cn: String, keys: KeyPair = keyPair()): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$cn")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000),
            Date(now + 86_400_000),
            name,
            keys.public,
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    fun toPem(cert: X509Certificate): ByteArray {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return StringWriter().apply {
            write("-----BEGIN CERTIFICATE-----\n")
            write(b64)
            write("\n-----END CERTIFICATE-----\n")
        }.toString().toByteArray()
    }

    /**
     * The association data Town OS puts in a "3 1 1" TLSA record: SHA-256 of the
     * certificate's SubjectPublicKeyInfo.
     */
    fun spkiSha256(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
}
