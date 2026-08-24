package com.flute.terminal.sdk.data.auth

import com.flute.terminal.sdk.data.remote.FluteIdentityApi
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.data.store.SecureStore
import com.flute.terminal.sdk.data.store.StoreKeys
import com.flute.terminal.sdk.exception.FluteApiException
import com.flute.terminal.sdk.exception.FluteAuthenticationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the merchant-scoped OAuth token (client_credentials).
 *
 * - Credentials come from the persisted [CredentialStore] (supplied once by the ISV).
 * - The token + its expiry are **persisted encrypted** ([SecureStore]) so they survive process
 *   restarts — a cold start reuses a still-valid token instead of re-authenticating.
 * - [bearer] refreshes on demand [SKEW_MS] before expiry; [refresh] forces a new token (used by the
 *   [TokenRefreshScheduler]). Both are single-flight via [mutex].
 */
internal class TokenProvider(
    private val credentials: CredentialStore,
    private val identityApi: FluteIdentityApi,
    private val store: SecureStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    /** Expiry (epoch ms) of the current token, or 0 if none. Read by the refresh scheduler. */
    fun expiresAtMs(): Long = store.getLong(StoreKeys.TOKEN_EXPIRES_AT, 0L)

    /** Valid `Bearer <token>` header, refreshing only if missing or within the skew window. */
    suspend fun bearer(): String = mutex.withLock {
        val token = store.getString(StoreKeys.ACCESS_TOKEN)
        if (token != null && nowMs() < expiresAtMs() - SKEW_MS) {
            "Bearer $token"
        } else {
            "Bearer ${fetchAndPersist()}"
        }
    }

    /** Forces a new token regardless of the current one's remaining life. */
    suspend fun refresh(): String = mutex.withLock { fetchAndPersist() }

    fun clear() {
        store.remove(StoreKeys.ACCESS_TOKEN)
        store.remove(StoreKeys.TOKEN_EXPIRES_AT)
    }

    private suspend fun fetchAndPersist(): String {
        val clientId = credentials.clientId()
        val clientSecret = credentials.clientSecret()
        if (clientId == null || clientSecret == null) {
            throw FluteAuthenticationException("No API credentials provisioned. Call initialize() with clientId/clientSecret once.")
        }
        val response = try {
            apiCall { identityApi.token(clientId = clientId, clientSecret = clientSecret) }
        } catch (e: FluteApiException) {
            throw FluteAuthenticationException(e.message ?: "Failed to obtain OAuth token", e, e.details)
        } catch (t: Throwable) {
            throw FluteAuthenticationException("Failed to obtain OAuth token", t)
        }
        store.putString(StoreKeys.ACCESS_TOKEN, response.accessToken)
        store.putLong(StoreKeys.TOKEN_EXPIRES_AT, nowMs() + response.expiresInSeconds * 1_000)
        return response.accessToken
    }

    companion object {
        const val SKEW_MS = 60_000L
    }
}
