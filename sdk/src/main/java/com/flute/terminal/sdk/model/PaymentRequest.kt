package com.flute.terminal.sdk.model

import com.flute.terminal.sdk.exception.InvalidPaymentParametersException
import java.math.BigDecimal

/**
 * Parameters for [com.flute.terminal.sdk.FluteTerminalLauncher.startPayment].
 *
 * Per ARISE-4280, the ISV supplies only what it knows about the sale. The SDK autofills the rest:
 * `terminalId` (resolved from the device serial at initialize) and `currencyCode` (from merchant
 * config) are NOT inputs here. `pricingType` is required only for Dual-Pricing merchants (enforced
 * at payment time against the merchant's config).
 */
data class PaymentRequest(
    /** Base amount (subtotal, before tip). Mandatory. */
    val baseAmount: BigDecimal,

    /** Required only when the merchant's ZCP option is Dual Pricing. */
    val pricingType: PricingType? = null,

    val captureMethod: CaptureMethod = CaptureMethod.AUTO,
    val readingMethod: ReadingMethod? = null,

    /** Omit to use the merchant's default processor. */
    val paymentProcessorId: String? = null,
    val customerId: String? = null,
    val referenceId: String? = null,

    /** ISV-owned device identifier (optional). */
    val posDeviceId: String? = null,

    /** Pre-defined tip. Supply amount OR rate, not both. Null asks for tip on the terminal. */
    val tipAmount: BigDecimal? = null,
    val tipRatePercent: BigDecimal? = null,

    val requestPaymentMethodStorageConsent: Boolean = false,
) {
    init {
        if (baseAmount <= BigDecimal.ZERO) {
            throw InvalidPaymentParametersException("baseAmount must be positive")
        }
        if (posDeviceId != null && posDeviceId.length > 36) {
            throw InvalidPaymentParametersException("posDeviceId must be <= 36 chars")
        }
        if (tipAmount != null && tipRatePercent != null) {
            throw InvalidPaymentParametersException("Supply either tipAmount or tipRatePercent, not both")
        }
    }

    /** Fluent builder for Java callers (Kotlin can use the constructor). */
    class Builder(private val baseAmount: BigDecimal) {
        private var pricingType: PricingType? = null
        private var captureMethod: CaptureMethod = CaptureMethod.AUTO
        private var readingMethod: ReadingMethod? = null
        private var paymentProcessorId: String? = null
        private var customerId: String? = null
        private var referenceId: String? = null
        private var posDeviceId: String? = null
        private var tipAmount: BigDecimal? = null
        private var tipRatePercent: BigDecimal? = null
        private var requestPaymentMethodStorageConsent: Boolean = false

        fun pricingType(value: PricingType?) = apply { pricingType = value }
        fun captureMethod(value: CaptureMethod) = apply { captureMethod = value }
        fun readingMethod(value: ReadingMethod?) = apply { readingMethod = value }
        fun paymentProcessorId(value: String?) = apply { paymentProcessorId = value }
        fun customerId(value: String?) = apply { customerId = value }
        fun referenceId(value: String?) = apply { referenceId = value }
        fun posDeviceId(value: String?) = apply { posDeviceId = value }
        fun tipAmount(value: BigDecimal?) = apply { tipAmount = value }
        fun tipRatePercent(value: BigDecimal?) = apply { tipRatePercent = value }
        fun requestPaymentMethodStorageConsent(value: Boolean) = apply { requestPaymentMethodStorageConsent = value }

        fun build() = PaymentRequest(
            baseAmount, pricingType, captureMethod, readingMethod, paymentProcessorId,
            customerId, referenceId, posDeviceId, tipAmount, tipRatePercent, requestPaymentMethodStorageConsent,
        )
    }
}
