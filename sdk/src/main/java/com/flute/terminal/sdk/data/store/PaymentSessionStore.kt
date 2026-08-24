package com.flute.terminal.sdk.data.store

/**
 * Persists the in-flight payment session (the created POS transaction id) so that a killed ISV
 * app can reconcile the payment against the API on next launch instead of losing the charge.
 * At most one session at a time — a countertop device runs one payment at a time by design.
 */
internal class PaymentSessionStore(
    private val store: SecureStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    data class PendingSession(val posTransactionId: String, val startedAtMs: Long)

    fun begin(posTransactionId: String) {
        store.putString(StoreKeys.PENDING_POS_TRANSACTION_ID, posTransactionId)
        store.putLong(StoreKeys.PENDING_STARTED_AT, nowMs())
    }

    fun current(): PendingSession? =
        store.getString(StoreKeys.PENDING_POS_TRANSACTION_ID)?.let {
            PendingSession(it, store.getLong(StoreKeys.PENDING_STARTED_AT, 0L))
        }

    /**
     * A session this old can no longer produce a meaningful outcome (the backend's stuck-
     * transaction handling has long since closed the record) — recovery reports it expired and
     * clears it instead of re-checking it on every app start forever.
     */
    fun isStale(session: PendingSession): Boolean =
        nowMs() - session.startedAtMs > MAX_PENDING_AGE_MS

    fun clear() {
        store.remove(StoreKeys.PENDING_POS_TRANSACTION_ID)
        store.remove(StoreKeys.PENDING_STARTED_AT)
    }

    companion object {
        const val MAX_PENDING_AGE_MS = 24 * 60 * 60 * 1_000L
    }
}
