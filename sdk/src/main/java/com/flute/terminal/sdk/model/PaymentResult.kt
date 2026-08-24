package com.flute.terminal.sdk.model

/**
 * Typed outcome delivered to the ISV callback. Sealed rather than an enum because each state
 * carries different data.
 *
 * IMPORTANT (ARISE-4282, two-level status): a POS transaction is a payment *intent*. Its
 * lifecycle status reaching "Completed" does NOT mean approved — the payment outcome lives on the
 * linked transaction, which may be a decline. So [Approved] vs [Declined] here must be derived
 * from the linked-transaction outcome, not merely from the POS transaction reaching a terminal
 * state — the API record is the source of truth, not the terminal.
 */
sealed class PaymentResult {

    /**
     * Approved payment with the full receipt-grade record. Fields beyond the ids are populated
     * from the canonical API record at resolution; a best-effort delivery from the terminal hint
     * alone (API unreachable) carries ids/authCode only.
     */
    data class Approved(
        val posTransactionId: String,
        val transactionId: String?,
        val authCode: String?,
        val responseCode: String?,
        /** Opaque receipt payload for the ISV to render/print. */
        val receiptData: String?,
        /** Total actually charged — reconcile orders against THIS, not the requested base. */
        val processedAmount: java.math.BigDecimal? = null,
        val amounts: AmountBreakdown? = null,
        val card: CardInfo? = null,
        /** Auth code / RRN / MID / TID — receipt + dispute references. */
        val processor: ProcessorReferences? = null,
        val avs: AvsResult? = null,
        /** Gateway reference of the linked transaction. */
        val gatewayReferenceId: String? = null,
        val transactionDateTime: String? = null,
        /** Refundable remainder — what a future refund/reversal can operate on. */
        val availableRefundAmount: java.math.BigDecimal? = null,
        /** Gateway transaction type, e.g. "Sale", "Authorization". */
        val transactionType: String? = null,
    ) : PaymentResult()

    data class Declined(
        val posTransactionId: String?,
        val transactionId: String?,
        val responseCode: String?,
        val message: String?,
        /** Amount the decline was attempted for (with ZCP/tip applied). */
        val processedAmount: java.math.BigDecimal? = null,
        val amounts: AmountBreakdown? = null,
        val card: CardInfo? = null,
        val avs: AvsResult? = null,
        val gatewayReferenceId: String? = null,
        val transactionDateTime: String? = null,
    ) : PaymentResult()

    data class Error(
        val reason: ErrorReason,
        val message: String?,
        /** Present when the POS transaction record was created before the failure. */
        val posTransactionId: String? = null,
        /** Flute trace id for support/debugging, when the failure came from the API. */
        val correlationId: String? = null,
    ) : PaymentResult()
}

enum class ErrorReason {
    APP_NOT_INSTALLED,
    UNAUTHORIZED_CALLER,
    TIMEOUT,
    TRANSACTION_CREATION_FAILED,
    AUTHENTICATION_FAILED,
    MALFORMED_RESPONSE,
    USER_CANCELLED,

    /** The terminal flow itself failed (POS transaction reached Failed). */
    TERMINAL_FAILED,

    /** startPayment was called while another payment is still running (a terminal runs one at a time). */
    ALREADY_IN_PROGRESS,

    /**
     * The SDK has no active configuration: `initialize()` was never called, or `shutdown()` ran.
     * Call `initialize()` again before taking payments.
     */
    NOT_INITIALIZED,
    UNKNOWN,
}
