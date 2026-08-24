package com.flute.terminal.sdk.model

/**
 * Capture behavior for a POS transaction. Maps 1:1 to IsvApiBff v2 `CaptureMethod`.
 *
 * NOTE (ARISE-4281 scope pushback): the epic's proposed `TransactionType { SALE, REFUND, VOID }`
 * does not map to POS-create. POS-create only starts a Sale (Auto) or an Authorization (Manual).
 * REFUND/VOID are operations on an *existing* transaction (`/v2/transactions/{id}/reversal|credit`)
 * and belong to separate SDK methods, not [FluteTerminalLauncher.startPayment].
 */
enum class CaptureMethod {
    /** Sale — funds captured immediately. */
    AUTO,

    /**
     * Authorization only. The SDK does NOT provide the follow-up capture — the authorization must
     * be completed (or allowed to expire) by the ISV's own backend via
     * `POST /v2/transactions/{transactionId}/capture`, using the `transactionId` from
     * [PaymentResult.Approved]. Use [AUTO] unless that server-side capture flow exists.
     */
    MANUAL,
}

/**
 * Dual-pricing selection. Maps to IsvApiBff v2 `PricingType`.
 * Mandatory only when the merchant's zero-cost-processing option is Dual Pricing.
 */
enum class PricingType {
    CARD,
    CASH,
}

/**
 * How the card is read. Maps to IsvApiBff v2 `PosReadingMethod`.
 * Note the API has no "tender type" concept (the epic's CARD/CASH/CONTACTLESS does not exist);
 * tap/insert/swipe are all [REGULAR].
 */
enum class ReadingMethod {
    /** Tap, insert, or swipe. */
    REGULAR,

    /** Card number keyed in on the terminal. */
    KEYED_ENTRY,
}
