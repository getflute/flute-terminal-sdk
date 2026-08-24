package com.flute.terminal.sdk.data.auth

import com.flute.terminal.sdk.data.remote.FluteIdentityApi
import com.flute.terminal.sdk.data.remote.dto.TokenResponse
import com.flute.terminal.sdk.data.store.InMemorySecureStore
import com.flute.terminal.sdk.data.store.SecureStore
import com.flute.terminal.sdk.exception.FluteAuthenticationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TokenProviderTest {

    private class FakeIdentityApi(private val expiresIn: Long = 3600) : FluteIdentityApi {
        var calls = 0
        override suspend fun token(grantType: String, clientId: String, clientSecret: String): TokenResponse {
            calls++
            return TokenResponse(accessToken = "tok-$calls", tokenType = "Bearer", expiresInSeconds = expiresIn)
        }
    }

    private fun provider(
        store: SecureStore,
        api: FluteIdentityApi,
        now: () -> Long,
        withCreds: Boolean = true,
    ): TokenProvider {
        val credentials = CredentialStore(store)
        if (withCreds) credentials.save("cid", "secret")
        return TokenProvider(credentials, api, store, nowMs = now)
    }

    @Test
    fun `caches token until near expiry`() = runTest {
        val store = InMemorySecureStore()
        val api = FakeIdentityApi(expiresIn = 3600)
        var now = 0L
        val p = provider(store, api, { now })

        assertEquals("Bearer tok-1", p.bearer())
        now += 60_000
        assertEquals("Bearer tok-1", p.bearer())
        assertEquals(1, api.calls)
    }

    @Test
    fun `refreshes within skew window of expiry`() = runTest {
        val store = InMemorySecureStore()
        val api = FakeIdentityApi(expiresIn = 120) // skew is 60s
        var now = 0L
        val p = provider(store, api, { now })

        assertEquals("Bearer tok-1", p.bearer())
        now += 61_000
        assertEquals("Bearer tok-2", p.bearer())
        assertEquals(2, api.calls)
    }

    @Test
    fun `persisted token is reused by a fresh provider without re-authenticating`() = runTest {
        val store = InMemorySecureStore()
        val api = FakeIdentityApi(expiresIn = 3600)
        val now = { 0L }

        assertEquals("Bearer tok-1", provider(store, api, now).bearer())
        // Simulate process restart: new provider, same encrypted store.
        assertEquals("Bearer tok-1", provider(store, api, now).bearer())
        assertEquals(1, api.calls)
    }

    @Test
    fun `refresh forces a new token even when current one is valid`() = runTest {
        val store = InMemorySecureStore()
        val api = FakeIdentityApi(expiresIn = 3600)
        val p = provider(store, api, { 0L })

        assertEquals("Bearer tok-1", p.bearer())
        assertEquals("tok-2", p.refresh())
        assertEquals(2, api.calls)
    }

    @Test
    fun `bearer throws when no credentials provisioned`() {
        val store = InMemorySecureStore()
        val api = FakeIdentityApi()
        val p = provider(store, api, { 0L }, withCreds = false)

        assertThrows(FluteAuthenticationException::class.java) { runTest { p.bearer() } }
    }
}
