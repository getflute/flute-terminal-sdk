package com.flute.terminal.sdk.data.auth

import com.flute.terminal.sdk.data.remote.FluteIdentityApi
import com.flute.terminal.sdk.data.remote.dto.TokenResponse
import com.flute.terminal.sdk.data.store.InMemorySecureStore
import com.flute.terminal.sdk.data.store.StoreKeys
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenRefreshSchedulerTest {

    private val skew = 60_000L

    private class CountingIdentityApi : FluteIdentityApi {
        var calls = 0
        override suspend fun token(grantType: String, clientId: String, clientSecret: String): TokenResponse {
            calls++
            return TokenResponse(accessToken = "tok-$calls", tokenType = "Bearer", expiresInSeconds = 900)
        }
    }

    @Test
    fun `wakes but skips the fetch when the token was renewed while sleeping`() = runTest {
        val store = InMemorySecureStore()
        val api = CountingIdentityApi()
        val credentials = CredentialStore(store).apply { save("cid", "secret") }
        val provider = TokenProvider(credentials, api, store, nowMs = { currentTime })

        // A valid token expiring at t=600s: the loop arms to wake at t=540s (expiry - skew).
        store.putString(StoreKeys.ACCESS_TOKEN, "tok-0")
        store.putLong(StoreKeys.TOKEN_EXPIRES_AT, 600_000L)

        val scheduler = TokenRefreshScheduler(backgroundScope, provider, nowMs = { currentTime })
        scheduler.start()
        advanceTimeBy(100_000)

        // Another actor (on-demand bearer, second instance) renews the token while the loop sleeps.
        store.putLong(StoreKeys.TOKEN_EXPIRES_AT, 2_000_000L)

        advanceTimeBy(500_000) // past the original wake time — the loop must re-arm, not fetch
        assertEquals(0, api.calls)
        scheduler.stop()
    }

    @Test
    fun `no token yet refreshes immediately`() {
        assertEquals(0L, TokenRefreshScheduler.nextDelayMs(expiresAtMs = 0L, nowMs = 5_000L, skewMs = skew))
    }

    @Test
    fun `already within skew window refreshes immediately`() {
        // expiry in 30s, skew 60s -> already inside the window
        assertEquals(0L, TokenRefreshScheduler.nextDelayMs(expiresAtMs = 30_000L, nowMs = 0L, skewMs = skew))
    }

    @Test
    fun `otherwise waits until expiry minus skew`() {
        // expiry at 10 min, now 0, skew 60s -> wait 9 min
        assertEquals(540_000L, TokenRefreshScheduler.nextDelayMs(expiresAtMs = 600_000L, nowMs = 0L, skewMs = skew))
    }
}
