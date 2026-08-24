package com.flute.terminal.sdk.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry policy is a money-safety boundary, not a convenience: replaying a create or reversal
 * POST after a timeout could produce a second transaction (double charge / double refund), because
 * the first may have been processed even though the response never arrived. Only idempotent reads
 * may be retried; everything else surfaces to the caller and is reconciled via pending-session
 * recovery.
 */
class RetryInterceptorTest {

    @Test
    fun `idempotent GET is retried while attempts remain`() {
        assertTrue(RetryInterceptor.isRetryable("GET", attempt = 1, maxAttempts = 3))
        assertTrue(RetryInterceptor.isRetryable("get", attempt = 2, maxAttempts = 3))
    }

    @Test
    fun `GET stops at the attempt limit`() {
        assertFalse(RetryInterceptor.isRetryable("GET", attempt = 3, maxAttempts = 3))
    }

    @Test
    fun `money-moving methods are never retried`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertFalse(
                "$method must never be replayed — it could duplicate a transaction",
                RetryInterceptor.isRetryable(method, attempt = 1, maxAttempts = 3),
            )
        }
    }
}
