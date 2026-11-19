package com.flute.terminal.sdk.model

/** Terminal mode. Mirrors backend `TerminalMode` (Hybrid added by ARISE-4290). */
enum class TerminalMode { STANDALONE, SEMI_INTEGRATED, HYBRID, UNKNOWN }

/** Merchant zero-cost-processing mode. Mirrors backend `ZeroCostProcessingOption`. */
enum class ZeroCostOption { NONE, CASH_DISCOUNT, DUAL_PRICING, SURCHARGE, UNKNOWN }

/** A terminal as surfaced by `GET /v2/terminals`. */
data class TerminalInfo(
    val id: String,
    val mode: TerminalMode,
    val isOnline: Boolean,
    /** Hardware serial printed on the device — the human-facing identifier operators recognize. */
    val serialNumber: String? = null,
) {
    /** Can this terminal accept a deeplink-initiated POS transaction? */
    val canAcceptPosTransaction: Boolean
        get() = isOnline && (mode == TerminalMode.SEMI_INTEGRATED || mode == TerminalMode.HYBRID)
}

/**
 * Merchant payment configuration from `GET /v2/settings/payment-configuration` (or, on gateways
 * without that route, the legacy `GET /v2/settings/payment-config`).
 */
data class PaymentConfig @JvmOverloads constructor(
    val currencyCode: String?,
    val zeroCostOption: ZeroCostOption,
    val defaultProcessorId: String?,
    val processorIds: List<String>,
    /** Merchant's registered company name. */
    val merchantCompanyName: String? = null,
    /** Whether the merchant collects tips. */
    val isTipsEnabled: Boolean = false,
    /** Tip percentages offered to the customer, as raw percent (15 = 15%). */
    val defaultTipOptions: List<java.math.BigDecimal> = emptyList(),
    /** The merchant's processors, with name and type. [processorIds] lists the same ids. */
    val processors: List<PaymentProcessor> = emptyList(),
) {
    /** Dual-pricing merchants must supply a [PricingType] on every transaction. */
    val requiresPricingType: Boolean get() = zeroCostOption == ZeroCostOption.DUAL_PRICING
}

/** A payment processor configured for the merchant. */
data class PaymentProcessor(
    val id: String,
    /** Ops-configured label, typically the merchant's DBA name. */
    val name: String?,
    /** Processor type as the API reports it, for example `Tsys`. */
    val type: String?,
    val isDefault: Boolean,
)
