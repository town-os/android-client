package com.townos.client.data

import android.content.SharedPreferences

/**
 * An in-memory [SharedPreferences].
 *
 * Store takes the `SharedPreferences` *interface*, so its logic can be tested
 * with a plain implementation and no Android framework at all — which matters
 * here: Robolectric has no Linux/aarch64 native runtime, so a framework-backed
 * unit test cannot run on this project's own dev machine. Implementing the
 * interface keeps these tests in `make test` on every host.
 *
 * Only the operations Store actually uses are implemented; the rest throw rather
 * than silently returning a default, so a future Store change cannot quietly
 * start relying on an unimplemented method.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun getAll(): MutableMap<String, *> = values

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        // Writes are staged and only applied on apply()/commit(), matching the
        // real contract — a Store that forgot to call apply() must fail here too.
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            staged[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            removed.forEach { values.remove(it) }
            staged.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }

        override fun clear(): SharedPreferences.Editor = apply { values.clear() }

        override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
        override fun putInt(key: String, value: Int) = unsupported()
        override fun putLong(key: String, value: Long) = unsupported()
        override fun putFloat(key: String, value: Float) = unsupported()
        override fun putBoolean(key: String, value: Boolean) = unsupported()
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = unsupported()
    override fun getInt(key: String?, defValue: Int) = unsupported()
    override fun getLong(key: String?, defValue: Long) = unsupported()
    override fun getFloat(key: String?, defValue: Float) = unsupported()
    override fun getBoolean(key: String?, defValue: Boolean) = unsupported()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = unsupported()

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("not used by Store; implement it if that changes")
}
