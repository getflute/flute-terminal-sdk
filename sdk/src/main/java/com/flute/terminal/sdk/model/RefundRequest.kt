package com.flute.terminal.sdk.model

import com.flute.terminal.sdk.exception.InvalidPaymentParametersException
import java.math.BigDecimal

/**
 * Parameters for [com.flute.terminal.sdk.FluteTerminalLauncher.startRefund] — an **unreferenced
 * refund** (return without reference): money back to a card the customer presents on the terminal,
 * with no originating transaction.
 *
 * As with a payment, the ISV supplies only what it knows: `terminalId` (device serial) and
 * `currencyCode` (merchant config) are autofilled by the SDK.
 *
 * There is deliberately no `originalTransactionId` here. Refunding a *known* transaction is a
 * referenced reversal/void — a cloud operation, out of this SDK's scope; use the API from your
 * backend for that.
 */
data class RefundRequest(
    /** Amount to return to the customer. Mandatory, positive. */
    val refundAmount: BigDecimal,

    /** ISV-owned device identifier. Mandatory for a refund (max 36 chars). */
    val posDeviceId: String,

    /** External reference for reconciliation (max 36 chars). Generated if omitted. */
    val referenceId: String? = null,

    /** How the customer presents the card; null lets the terminal decide. */
    val readingMethod: ReadingMethod? = null,

    /** Omit to use the merchant's default processor. */
    val paymentProcessorId: String? = null,
    val customerId: String? = null,
) {
    init {
        if (refundAmount <= BigDecimal.ZERO) {
            throw InvalidPaymentParametersException("refundAmount must be positive")
        }
        if (posDeviceId.length > 36) {
            throw InvalidPaymentParametersException("posDeviceId must be <= 36 chars")
        }
        if (referenceId != null && referenceId.length > 36) {
            throw InvalidPaymentParametersException("referenceId must be <= 36 chars")
        }
    }

    /** Fluent builder for Java callers (Kotlin can use the constructor). */
    class Builder(private var refundAmount: BigDecimal, private var posDeviceId: String) {
        private var referenceId: String? = null
        private var readingMethod: ReadingMethod? = null
        private var paymentProcessorId: String? = null
        private var customerId: String? = null

        fun referenceId(value: String?) = apply { referenceId = value }
        fun readingMethod(value: ReadingMethod?) = apply { readingMethod = value }
        fun paymentProcessorId(value: String?) = apply { paymentProcessorId = value }
        fun customerId(value: String?) = apply { customerId = value }

        fun build() = RefundRequest(
            refundAmount, posDeviceId, referenceId, readingMethod, paymentProcessorId, customerId,
        )
    }
}
