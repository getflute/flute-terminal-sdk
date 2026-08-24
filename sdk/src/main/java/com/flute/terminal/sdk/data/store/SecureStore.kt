package com.flute.terminal.sdk.data.store

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal encrypted-at-rest key/value contract. Abstracted so the domain/data layers don't depend
 * on Android storage APIs and can be unit-tested with [InMemorySecureStore].
 */
internal interface SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

/**
 * Scopes every key to one namespace (the target environment).
 *
 * Persisted state — token, payment config, resolved terminalId, pending session, credentials — is
 * environment-specific: a UAT token is rejected by DEV, and the same terminal has a different
 * terminalId per environment. Without this, switching environments would silently reuse the previous
 * environment's state against the new host. Namespacing also means switching back and forth doesn't
 * discard either environment's warm state.
 */
internal class NamespacedSecureStore(
    private val delegate: SecureStore,
    private val namespace: String,
) : SecureStore {
    private fun key(key: String) = "$namespace/$key"

    override fun getString(key: String): String? = delegate.getString(key(key))
    override fun putString(key: String, value: String) = delegate.putString(key(key), value)
    override fun getLong(key: String, default: Long): Long = delegate.getLong(key(key), default)
    override fun putLong(key: String, value: Long) = delegate.putLong(key(key), value)
    override fun remove(key: String) = delegate.remove(key(key))
}

/** In-memory store for unit tests (and a safe fallback). NOT persistent. */
internal class InMemorySecureStore : SecureStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getLong(key: String, default: Long): Long = map[key]?.toLongOrNull() ?: default
    override fun putLong(key: String, value: Long) { map[key] = value.toString() }
    override fun remove(key: String) { map.remove(key) }
}

internal object StoreKeys {
    const val CLIENT_ID = "client_id"
    const val CLIENT_SECRET = "client_secret"
    /** Marks credentials as runtime-provisioned so config-supplied ones never overwrite them. */
    const val CREDENTIALS_SOURCE = "credentials_source"
    const val ACCESS_TOKEN = "access_token"
    const val TOKEN_EXPIRES_AT = "token_expires_at"
    const val PAYMENT_CONFIG = "payment_config_json"
    const val PENDING_POS_TRANSACTION_ID = "pending_pos_transaction_id"
    const val PENDING_STARTED_AT = "pending_started_at"

    /** Resolved terminal id + the serial it was resolved for (a serial change invalidates it). */
    const val TERMINAL_ID = "terminal_id"
    const val TERMINAL_ID_SERIAL = "terminal_id_serial"
}
