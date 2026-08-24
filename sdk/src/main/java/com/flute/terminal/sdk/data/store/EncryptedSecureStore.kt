package com.flute.terminal.sdk.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [SecureStore] backed by [EncryptedSharedPreferences] — AES-256, keys held in the Android
 * Keystore. This is where the ISV's client secret, the OAuth token, and cached config live at
 * rest. Never log these values.
 */
internal class EncryptedSecureStore(context: Context) : SecureStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private companion object {
        const val PREFS_FILE = "flute_terminal_sdk_secure"
    }
}
