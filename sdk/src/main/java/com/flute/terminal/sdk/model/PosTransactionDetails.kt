package com.flute.terminal.sdk.model

/** Lifecycle status of a POS transaction (payment intent). Mirrors the v2 aggregated status. */
enum class PosTransactionStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED,
    UNKNOWN,
}

/**
 * Canonical POS transaction record fetched from the API (`GET /v2/pos/transactions/{id}`).
 *
 * A POS transaction is a payment *intent*: [status] = COMPLETED only means the terminal flow
 * finished — the actual payment outcome (approval vs decline) lives on [linkedOutcome].
 */
data class PosTransactionDetails(
    val posTransactionId: String,
    val status: PosTransactionStatus,
    /** Gateway transaction id; null until the POS transaction completes. */
    val transactionId: String?,
    /** Payment outcome from the linked transaction; null until completed. */
    val linkedOutcome: LinkedTransactionOutcome?,
)

/** Outcome of the linked payment transaction, carrying the full receipt-grade record. */
data class LinkedTransactionOutcome(
    /** Raw aggregated transaction status from the gateway (e.g. "Captured", "Declined"). */
    val status: String,
    val responseCode: String?,
    val responseMessage: String?,
    /** Gateway transaction type, e.g. "Sale", "Authorization". */
    val transactionType: String? = null,
    /** Gateway-assigned reference for the linked transaction. */
    val referenceId: String? = null,
    val transactionDateTime: String? = null,
    /** Total actually processed (differs from base on ZCP/tip sales). */
    val processedAmount: java.math.BigDecimal? = null,
    val amounts: AmountBreakdown? = null,
    val card: CardInfo? = null,
    val processor: ProcessorReferences? = null,
    val avs: AvsResult? = null,
    /** How much of this transaction can still be refunded. */
    val availableRefundAmount: java.math.BigDecimal? = null,
) {
    /** Aggregated statuses that mean the money moved, for this transaction's type. */
    val isApproved: Boolean
        get() = ApprovedStatuses.contains(status, transactionType)
}
