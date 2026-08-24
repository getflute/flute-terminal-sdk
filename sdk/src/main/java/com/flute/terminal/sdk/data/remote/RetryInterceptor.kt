package com.flute.terminal.sdk.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retries transient network failures — but **only for idempotent requests**.
 *
 * A terminal runs on shop wifi and the platform occasionally slows down (observed live: a token call
 * at 9s and `payment-config` timing out while `terminals` answered in 1.6s), so a single blip should
 * not fail a sale.
 *
 * SAFETY: only GET is retried. Replaying `POST /v2/pos/transactions` or `/reversal` after a timeout
 * could create a **second** transaction — a double charge or double refund — because the first
 * request may well have been processed even though the response never arrived. Those failures are
 * surfaced to the caller instead, and the SDK's pending-session recovery reconciles them.
 */
internal class RetryInterceptor(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val backoffMs: Long = BACKOFF_MS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastFailure: IOException? = null

        for (attempt in 1..maxAttempts) {
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                lastFailure = e
                if (!isRetryable(request.method, attempt, maxAttempts)) throw e
                // Linear backoff: the goal is riding out a brief slowdown, not hammering a
                // struggling service.
                sleep(backoffMs * attempt)
            }
        }
        throw lastFailure ?: IOException("Request failed")
    }

    internal companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 500L

        /** Pure decision (unit-tested): retry only idempotent methods, only while attempts remain. */
        fun isRetryable(method: String, attempt: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean =
            attempt < maxAttempts && method.equals("GET", ignoreCase = true)
    }
}
