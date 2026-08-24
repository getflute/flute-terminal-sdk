package com.flute.terminal.sdk.data.mapper

import com.flute.terminal.sdk.data.remote.dto.CalculateAmountResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Guards the v2 `GET /v2/transactions/calculate-amount` contract. The dual-pricing spread lives in
 * each tender's base/total (Cash < CreditCard), with discount/surcharge zero — the SDK must carry
 * those per-tender totals and the ZCP mode into the terminal payload.
 */
class CalculateAmountMappingTest {

    private val gson = Gson()

    private val dualPricingBody = """
        {
          "currencyCode":"USD",
          "zeroCostProcessingOption":"DualPricing",
          "pricingType":"Card",
          "cash":{"baseAmount":11.98,"discountAmount":0,"discountRate":0,"surchargeAmount":0,"surchargeRate":0,"tipAmount":0,"tipRate":0,"totalAmount":11.98},
          "creditCard":{"baseAmount":12.34,"discountAmount":0,"discountRate":0,"surchargeAmount":0,"surchargeRate":0,"tipAmount":0,"tipRate":0,"totalAmount":12.34},
          "debitCard":{"baseAmount":12.34,"discountAmount":0,"discountRate":0,"surchargeAmount":0,"surchargeRate":0,"tipAmount":0,"tipRate":0,"totalAmount":12.34},
          "ach":{"baseAmount":11.98,"discountAmount":0,"discountRate":0,"surchargeAmount":0,"surchargeRate":0,"tipAmount":0,"tipRate":0,"totalAmount":11.98}
        }
    """.trimIndent()

    @Test
    fun `maps the dual-pricing projection to per-tender totals and ZCP mode`() {
        val amounts = Mappers.toCalculatedAmounts(gson.fromJson(dualPricingBody, CalculateAmountResponse::class.java))

        assertEquals("DualPricing", amounts.zeroCostOption)
        assertEquals(BigDecimal("11.98"), amounts.cash?.totalAmount)
        assertEquals(BigDecimal("12.34"), amounts.creditCard?.totalAmount)
        // The card price exceeds the cash price — the dual-pricing spread the terminal must display.
        assertEquals(true, amounts.creditCard!!.totalAmount!! > amounts.cash!!.totalAmount!!)
    }
}
