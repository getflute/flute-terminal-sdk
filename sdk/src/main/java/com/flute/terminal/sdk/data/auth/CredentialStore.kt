package com.flute.terminal.sdk.data.auth

import com.flute.terminal.sdk.data.store.SecureStore
import com.flute.terminal.sdk.data.store.StoreKeys

/**
 * Persists the ISV's merchant-scoped API credentials. The ISV supplies `clientId`/`clientSecret`
 * once (e.g. at provisioning); the SDK stores them encrypted and reuses them on every later launch,
 * so they need not be passed again.
 */
internal class CredentialStore(private val store: SecureStore) {

    /** Credentials seeded from [com.flute.terminal.sdk.FluteTerminalConfig] at initialize. */
    fun save(clientId: String, clientSecret: String) {
        store.putString(StoreKeys.CLIENT_ID, clientId)
        store.putString(StoreKeys.CLIENT_SECRET, clientSecret)
    }

    /**
     * Credentials provisioned at runtime (operator entry, ISV backend hand-off). These **outrank**
     * config-supplied ones: an app that passes credentials in its config on every launch would
     * otherwise overwrite what was just provisioned on the next initialize, so provisioning would
     * appear not to stick. Recorded per environment, since the store is environment-scoped.
     */
    fun saveProvisioned(clientId: String, clientSecret: String) {
        save(clientId, clientSecret)
        store.putString(StoreKeys.CREDENTIALS_SOURCE, SOURCE_RUNTIME)
    }

    /** True once [saveProvisioned] has been used for this environment. */
    fun wasProvisionedAtRuntime(): Boolean =
        store.getString(StoreKeys.CREDENTIALS_SOURCE) == SOURCE_RUNTIME

    fun clientId(): String? = store.getString(StoreKeys.CLIENT_ID)
    fun clientSecret(): String? = store.getString(StoreKeys.CLIENT_SECRET)
    fun hasCredentials(): Boolean = clientId() != null && clientSecret() != null

    fun clear() {
        store.remove(StoreKeys.CLIENT_ID)
        store.remove(StoreKeys.CLIENT_SECRET)
        store.remove(StoreKeys.CREDENTIALS_SOURCE)
    }

    private companion object {
        const val SOURCE_RUNTIME = "runtime"
    }
}
