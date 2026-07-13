package com.townos.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted box + tunnel state.
 *
 * This holds a WireGuard private key and a session token, so it is backed by
 * EncryptedSharedPreferences (keys in the Android keystore) rather than plain
 * SharedPreferences.
 */
class Store(private val prefs: SharedPreferences) {

    /** e.g. "192.168.122.50" or "http://192.168.122.50:5309". */
    var boxAddress: String?
        get() = prefs.getString(KEY_BOX, null)
        set(value) = prefs.edit().putString(KEY_BOX, value).apply()

    /** Invalidated by every box reboot — treat a 401 as "log in again". */
    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Generated on-device at enrollment; never sent to the box. */
    var privateKey: String?
        get() = prefs.getString(KEY_PRIVATE_KEY, null)
        set(value) = prefs.edit().putString(KEY_PRIVATE_KEY, value).apply()

    /** The wg-quick config returned by POST /networks/peers/add, verbatim. */
    var peerConfig: String?
        get() = prefs.getString(KEY_PEER_CONFIG, null)
        set(value) = prefs.edit().putString(KEY_PEER_CONFIG, value).apply()

    var networkName: String?
        get() = prefs.getString(KEY_NETWORK, null)
        set(value) = prefs.edit().putString(KEY_NETWORK, value).apply()

    /** The network's TLD — installed as a DNS search domain. */
    var tld: String?
        get() = prefs.getString(KEY_TLD, null)
        set(value) = prefs.edit().putString(KEY_TLD, value).apply()

    /**
     * Override for the resolver address. Empty means "use the DNS = line from
     * the box's config". Set this when rolodex is bound somewhere other than the
     * overlay .1 — on a stock box it binds the default-route interface's
     * addresses (e.g. 192.168.122.50), not the WireGuard address.
     */
    var dnsOverride: String?
        get() = prefs.getString(KEY_DNS_OVERRIDE, null)
        set(value) = prefs.edit().putString(KEY_DNS_OVERRIDE, value).apply()

    /** Override for the tunnel endpoint, when the box advertised a bad one. */
    var endpointOverride: String?
        get() = prefs.getString(KEY_ENDPOINT_OVERRIDE, null)
        set(value) = prefs.edit().putString(KEY_ENDPOINT_OVERRIDE, value).apply()

    /** PEM of the box's local CA, from GET /tls/ca.crt. */
    var caPem: String?
        get() = prefs.getString(KEY_CA_PEM, null)
        set(value) = prefs.edit().putString(KEY_CA_PEM, value).apply()

    /**
     * SHA-256 of the CA we pinned on first contact. Fetching the CA is
     * trust-on-first-use; a *change* afterwards is what we can actually detect,
     * and it is a red flag worth showing the user.
     */
    var caFingerprint: String?
        get() = prefs.getString(KEY_CA_FINGERPRINT, null)
        set(value) = prefs.edit().putString(KEY_CA_FINGERPRINT, value).apply()

    val enrolled: Boolean get() = !peerConfig.isNullOrBlank() && !privateKey.isNullOrBlank()

    /** Forget the tunnel but keep the box address, so re-enrolling is one tap. */
    fun clearEnrollment() {
        prefs.edit()
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_PEER_CONFIG)
            .remove(KEY_NETWORK)
            .remove(KEY_TLD)
            .apply()
    }

    companion object {
        /**
         * The real thing: preferences encrypted with a key held in the Android
         * keystore. Kept behind a factory so the class itself depends only on
         * [SharedPreferences] — EncryptedSharedPreferences needs a hardware-backed
         * keystore that a JVM unit test cannot provide, and the *logic* here (what
         * we persist, and what `clearEnrollment` does and does not wipe) is worth
         * testing on its own.
         */
        fun encrypted(context: Context): Store {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return Store(
                EncryptedSharedPreferences.create(
                    context,
                    "townos",
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ),
            )
        }

        const val KEY_BOX = "box_address"
        const val KEY_TOKEN = "token"
        const val KEY_PRIVATE_KEY = "private_key"
        const val KEY_PEER_CONFIG = "peer_config"
        const val KEY_NETWORK = "network"
        const val KEY_TLD = "tld"
        const val KEY_DNS_OVERRIDE = "dns_override"
        const val KEY_ENDPOINT_OVERRIDE = "endpoint_override"
        const val KEY_CA_PEM = "ca_pem"
        const val KEY_CA_FINGERPRINT = "ca_fingerprint"
    }
}
