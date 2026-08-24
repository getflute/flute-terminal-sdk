package com.flute.terminal.sdk.model

import java.math.BigDecimal

/**
 * A gateway transaction — the record the post-payment operations read and return
 * (capture, reversal, tip adjustment, lookup). Same receipt-grade detail the SDK reports on an
 * approved payment, so an ISV never has to call the API for what an operation already returned.
 */
data class Transaction(
    val transactionId: String,
    /** Aggregated status, e.g. "Captured", "Authorized", "Voided", "Refunded", "Declined". */
    val status: String,
    /** Gateway transaction type, e.g. "Sale", "Authorization", "Refund", "Void". */
    val type: String?,
    /** Total actually processed — reconcile against this, not the amount requested. */
    val processedAmount: BigDecimal?,
    val currencyCode: String?,
    val amounts: AmountBreakdown?,
    val card: CardInfo?,
    /** Auth code / RRN / MID / TID — receipt + dispute references. */
    val processor: ProcessorReferences?,
    val avs: AvsResult?,
    /** Populated when [status] is a decline. */
    val declineCode: String?,
    val declineMessage: String?,
    val referenceId: String?,
    val transactionDateTime: String?,
    /** How much of this transaction can still be reversed/refunded. */
    val availableRefundAmount: BigDecimal?,
    val refundedAmount: BigDecimal?,
    /** Set on transactions derived from another (a refund/void points at its original). */
    val originalTransactionId: String?,
    val customerId: String?,
    val paymentProcessorId: String?,
    val batchId: String?,
    /** "Card" or "Ach". */
    val paymentMethodType: String?,
) {
    /** True when the transaction reached a state that means the money moved. */
    val isApproved: Boolean get() = ApprovedStatuses.contains(status, type)

    /** Nothing left to reverse — a fully refunded or voided transaction. */
    val isFullyRefunded: Boolean
        get() = availableRefundAmount?.signum() == 0
}

/** Aggregated statuses that mean the payment went through. Shared with the payment outcome mapping. */
internal object ApprovedStatuses {
    private val VALUES = setOf(
        "authorized", "captured", "settled", "partiallyauthorized", "verified", "cleared",
    )

    /**
     * A reversal succeeds into its own terminal status rather than one of the payment states above:
     * an approved refund reads "Refunded" and an approved void reads "Voided". Judging those by the
     * payment set reported a completed refund as DECLINED with the message "Refunded" — money out,
     * failure on screen. Kept type-scoped so a *sale* that later reads "Refunded" is not counted as
     * an approved sale.
     */
    private val REVERSAL_VALUES = setOf("refunded", "voided")
    private val REVERSAL_TYPES = setOf("refund", "refundworef", "void", "reversal")

    fun contains(status: String, transactionType: String? = null): Boolean {
        val normalized = status.lowercase()
        if (normalized in VALUES) return true
        return transactionType?.lowercase() in REVERSAL_TYPES && normalized in REVERSAL_VALUES
    }
}

/**
 * How a receipt is delivered by [com.flute.terminal.sdk.FluteTerminal.shareReceipt].
 *
 * SMS is the only channel the platform accepts: `POST /v2/transactions/{id}/share-receipt`
 * validates the channel against Sms and the recipient against E.164, so any other value is
 * rejected with a 400. The enum exists rather than a bare method so email can be added here once
 * the platform supports it.
 */
enum class ReceiptDeliveryMethod {
    SMS,
}
