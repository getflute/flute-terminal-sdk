package com.flute.terminal.sdk.model

import java.math.BigDecimal

/**
 * Gateway-computed per-tender amounts from `GET /v2/transactions/calculate-amount`. The SDK carries
 * these into the deeplink payload rather than computing ZCP/surcharge/tip math itself.
 *
 * NOTE: the public calculate-amount response is a simplified projection (amounts, no rate fields),
 * so this cannot fully reconstruct the terminal's internal `Amounts` for surcharge/dual-pricing
 * merchants — full fidelity needs the backend to return the notification envelope (ARISE-4420).
 */
data class CalculatedAmounts(
    val currencyCode: String?,
    /** Merchant ZCP mode for these amounts: None | CashDiscount | DualPricing | Surcharge (null = unknown). */
    val zeroCostOption: String?,
    val cash: TenderAmount?,
    val creditCard: TenderAmount?,
    val debitCard: TenderAmount?,
    val ach: TenderAmount?,
) {
    companion object {
        /** Flat amounts for a None-ZCP, no-tip sale: every tender total equals the base. No math. */
        fun flat(baseAmount: BigDecimal, currencyCode: String): CalculatedAmounts {
            val z = BigDecimal.ZERO
            val t = TenderAmount(baseAmount, z, z, z, z, z, z, baseAmount)
            return CalculatedAmounts(currencyCode, "None", t, t, t, t)
        }
    }
}

data class TenderAmount(
    val baseAmount: BigDecimal?,
    val discountAmount: BigDecimal?,
    val discountRate: BigDecimal?,
    val surchargeAmount: BigDecimal?,
    val surchargeRate: BigDecimal?,
    val tipAmount: BigDecimal?,
    val tipRate: BigDecimal?,
    val totalAmount: BigDecimal?,
)
