package com.flute.terminal.sdk.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the OAuth token warm: sleeps until shortly before expiry, then refreshes, and repeats.
 *
 * Deliberately a coroutine loop, not WorkManager — WorkManager's 15-minute floor is too coarse for
 * token TTLs, and cross-process durability isn't needed (the token is persisted, so a cold start
 * reuses it and [start] re-arms the loop). Lives only while the process is alive.
 */
internal class TokenRefreshScheduler(
    private val scope: CoroutineScope,
    private val tokenProvider: TokenProvider,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                val delayMs = nextDelayMs(tokenProvider.expiresAtMs(), nowMs())
                delay(delayMs)
                // The token may have been renewed while this loop slept (an on-demand bearer(), or
                // another SDK instance sharing the persisted store). If it is no longer within the
                // skew window, re-arm on the new expiry instead of forcing a redundant fetch.
                if (nextDelayMs(tokenProvider.expiresAtMs(), nowMs()) > 0L) continue
                try {
                    tokenProvider.refresh()
                } catch (t: Throwable) {
                    // Transient failure — back off and retry; on-demand bearer() still recovers.
                    delay(RETRY_BACKOFF_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Refresh this long before expiry (matches [TokenProvider.SKEW_MS]). */
        const val REFRESH_SKEW_MS = TokenProvider.SKEW_MS
        const val RETRY_BACKOFF_MS = 30_000L

        /**
         * Delay until the next refresh. Pure and clamped so it is unit-testable:
         *  - no token yet (expiry 0) → refresh immediately
         *  - already within the skew window → refresh immediately (0)
         *  - otherwise wait until (expiry - skew)
         */
        fun nextDelayMs(expiresAtMs: Long, nowMs: Long, skewMs: Long = REFRESH_SKEW_MS): Long {
            if (expiresAtMs <= 0L) return 0L
            return (expiresAtMs - skewMs - nowMs).coerceAtLeast(0L)
        }
    }
}
