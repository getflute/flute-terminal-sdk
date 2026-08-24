package com.flute.terminal.sdk.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSessionStoreTest {

    @Test
    fun `begin persists and clear removes the session`() {
        var now = 1_000L
        val store = PaymentSessionStore(InMemorySecureStore(), nowMs = { now })

        store.begin("pos-1")
        assertEquals(PaymentSessionStore.PendingSession("pos-1", 1_000L), store.current())

        store.clear()
        assertNull(store.current())
    }

    @Test
    fun `session becomes stale only after the max pending age`() {
        var now = 0L
        val store = PaymentSessionStore(InMemorySecureStore(), nowMs = { now })
        store.begin("pos-1")
        val session = store.current()!!

        now = PaymentSessionStore.MAX_PENDING_AGE_MS
        assertFalse(store.isStale(session))

        now = PaymentSessionStore.MAX_PENDING_AGE_MS + 1
        assertTrue(store.isStale(session))
    }
}
