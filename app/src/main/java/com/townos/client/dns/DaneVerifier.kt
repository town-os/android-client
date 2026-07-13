package com.townos.client.dns

import org.xbill.DNS.Lookup
import org.xbill.DNS.Name
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TLSARecord
import org.xbill.DNS.Type
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * DANE (RFC 6698) verification against the TLSA records the box publishes.
 *
 * Town OS pins every package's leaf certificate in DNS. For a package at
 * `<pkg>.<repo>.<tld>` served on :443 the box writes (src/rolodex/dns.go,
 * controller_tls.go):
 *
 *     _443._tcp.<pkg>.<repo>.<tld>.  IN TLSA  3 1 1 <hex sha256 of the leaf SPKI>
 *
 * i.e. usage 3 (DANE-EE — the end-entity cert directly, no CA path needed),
 * selector 1 (SubjectPublicKeyInfo), matching 1 (SHA-256).
 *
 * The records are dual-homed exactly like the A records: a *scoped* copy served
 * to overlay peers and a *global* copy served to the LAN. Which one we get back
 * depends on our source IP, so this must be queried through the tunnel to match
 * what we will actually connect to.
 *
 * What this does and does not buy us:
 *
 *  - It **does** let us verify a package's certificate without having fetched
 *    the CA out of band, and it catches a leaf that has been swapped for one
 *    signed by a different key.
 *  - It is **not** cryptographically anchored. Town OS publishes no DNSSEC chain
 *    for its private TLD, so a TLSA record is exactly as trustworthy as the
 *    resolver that handed it over. Since that resolver is the box itself,
 *    reached over an authenticated WireGuard tunnel, that is a defensible place
 *    to put trust — but it is trust in the tunnel, not in DNSSEC. Do not present
 *    it to the user as more than it is.
 */
class DaneVerifier(private val resolver: InetAddress) {

    data class Pin(
        val usage: Int,
        val selector: Int,
        val matchingType: Int,
        val data: ByteArray,
    ) {
        /** The only combination Town OS emits, and the only one we honour. */
        val isDaneEeSpkiSha256: Boolean
            get() = usage == 3 && selector == 1 && matchingType == 1

        override fun equals(other: Any?): Boolean =
            other is Pin && usage == other.usage && selector == other.selector &&
                matchingType == other.matchingType && data.contentEquals(other.data)

        override fun hashCode(): Int = data.contentHashCode() * 31 + usage
    }

    /**
     * Fetch the TLSA pins for a host/port. Empty means "no pins published" —
     * which is not the same as "verification failed", and callers must not treat
     * it as such (an unpublished service, or `dns_excluded_services`, both look
     * like this).
     */
    fun lookupPins(host: String, port: Int = 443): List<Pin> {
        val owner = Name.fromString("_${port}._tcp.${host.trimEnd('.')}.")

        // Query the box directly rather than going through Android's resolver:
        // android.net.DnsResolver cannot ask for arbitrary record types, and we
        // need the answer to come from the box (with our overlay source IP) so
        // we get the scoped view.
        val lookup = Lookup(owner, Type.TLSA).apply {
            setResolver(SimpleResolver(resolver))
            setCache(null)
        }

        val records = lookup.run()
        if (lookup.result != Lookup.SUCCESSFUL || records == null) return emptyList()

        return records.filterIsInstance<TLSARecord>().map {
            Pin(it.certificateUsage, it.selector, it.matchingType, it.certificateAssociationData)
        }
    }

    /**
     * Verify a server's leaf certificate against the published pins.
     *
     * Returns [Result.NoPins] when the box publishes nothing for this name, so
     * the caller can decide its own policy (fall back to CA validation, or
     * refuse). Returns [Result.Match] only on a pin we actually understand.
     */
    fun verify(host: String, port: Int, leaf: X509Certificate): Result =
        match(lookupPins(host, port), leaf)

    companion object {
        /**
         * The pure half of [verify]: compare a leaf against a set of pins.
         *
         * Split out from the DNS lookup so the matching rules — which are the
         * part that must not be wrong — can be tested without a resolver.
         */
        fun match(pins: List<Pin>, leaf: X509Certificate): Result {
            if (pins.isEmpty()) return Result.NoPins

            val usable = pins.filter { it.isDaneEeSpkiSha256 }
            if (usable.isEmpty()) return Result.UnsupportedPins(pins)

            // Selector 1 is SubjectPublicKeyInfo, and X509Certificate.publicKey
            // .encoded is exactly the DER SPKI — so this is a direct comparison,
            // no reparsing of the certificate required.
            val spkiSha256 = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)

            return if (usable.any { it.data.contentEquals(spkiSha256) }) {
                Result.Match
            } else {
                Result.Mismatch(expected = usable.map { it.data.toHex() }, actual = spkiSha256.toHex())
            }
        }

        /** Parse the RDATA Town OS writes, e.g. "3 1 1 ab12…". */
        fun parsePin(rdata: String): Pin? {
            val parts = rdata.trim().split(Regex("\\s+"))
            if (parts.size != 4) return null
            val usage = parts[0].toIntOrNull() ?: return null
            val selector = parts[1].toIntOrNull() ?: return null
            val matching = parts[2].toIntOrNull() ?: return null
            val data = parts[3].hexToBytesOrNull() ?: return null
            return Pin(usage, selector, matching, data)
        }
    }

    sealed interface Result {
        /** The leaf's SPKI matches a published pin. */
        data object Match : Result

        /** No TLSA records for this name. Caller decides the policy. */
        data object NoPins : Result

        /** Pins exist but none are DANE-EE/SPKI/SHA-256. */
        data class UnsupportedPins(val pins: List<Pin>) : Result

        /** Pins exist and the leaf matches none of them. This is a hard failure. */
        data class Mismatch(val expected: List<String>, val actual: String) : Result
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Decode a hex string, or null if it is not valid hex. */
internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0 || isEmpty()) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
