package com.flute.terminal.sdk.model

/**
 * Result of [com.flute.terminal.sdk.FluteTerminal.checkPendingPayment] — process-death recovery.
 *
 * If the ISV app was killed mid-payment, the SDK still holds the in-flight transaction id and can
 * reconcile against the API on next launch instead of losing the charge.
 */
data class PendingPaymentCheck(
    /** True if an unresolved payment from a previous run was found. */
    val hasPending: Boolean,
    /** True if that payment is still in progress on the terminal (check again later). */
    val stillInProgress: Boolean,
    /** Final outcome when the pending payment reached a terminal state; null otherwise. */
    val result: PaymentResult?,
) {
    companion object {
        @JvmStatic
        fun none() = PendingPaymentCheck(hasPending = false, stillInProgress = false, result = null)
    }
}
