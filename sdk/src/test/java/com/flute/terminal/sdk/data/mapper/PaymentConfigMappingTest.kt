package com.flute.terminal.sdk.data.mapper

import com.flute.terminal.sdk.data.remote.dto.PaymentConfigResponse
import com.flute.terminal.sdk.model.PaymentConfig
import com.flute.terminal.sdk.model.PaymentProcessor
import com.flute.terminal.sdk.model.ZeroCostOption
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Guards both payment-configuration contracts: the current `payment-configuration` route and the
 * legacy `payment-config` route, whose field names differ. Either must fill the same [PaymentConfig].
 */
class PaymentConfigMappingTest {

    private val gson = Gson()

    private val currentBody = """
        {
          "merchantId":"195c89de-b0f8-4768-a421-da07f71b1a7f",
          "merchantCompanyName":"Acme Grocery",
          "currencyCode":"USD",
          "zeroCostProcessingOption":"Surcharge",
          "pricingType":"Card",
          "isTipsEnabled":true,
          "defaultTipOptions":[10,15,20],
          "availablePaymentProcessors":[
            {"paymentProcessorId":"p-1","processorName":"Downtown Store","processorType":"Tsys","isDefault":false,"settlementBatchSchedule":[]},
            {"paymentProcessorId":"p-2","processorName":"Uptown Store","processorType":"Tsys","isDefault":true,"settlementBatchSchedule":[]}
          ]
        }
    """.trimIndent()

    private val legacyBody = """
        {
          "companyName":"Acme Grocery",
          "currency":"USD",
          "zeroCostProcessingOption":"DualPricing",
          "isTipsEnabled":true,
          "defaultTipsOptions":[18,20,25],
          "availablePaymentProcessors":[
            {"paymentProcessorId":"p-1","type":"Tsys","isDefault":true,"settlementBatchTimeSlots":[]}
          ]
        }
    """.trimIndent()

    private fun map(json: String) = Mappers.toConfig(gson.fromJson(json, PaymentConfigResponse::class.java))

    @Test
    fun `maps the current payment-configuration fields`() {
        val config = map(currentBody)

        assertEquals("USD", config.currencyCode)
        assertEquals("Acme Grocery", config.merchantCompanyName)
        assertEquals(ZeroCostOption.SURCHARGE, config.zeroCostOption)
        assertEquals(true, config.isTipsEnabled)
        assertEquals(listOf(BigDecimal("10"), BigDecimal("15"), BigDecimal("20")), config.defaultTipOptions)
        assertEquals("p-2", config.defaultProcessorId)
        assertEquals(listOf("p-1", "p-2"), config.processorIds)
        assertEquals(PaymentProcessor("p-2", "Uptown Store", "Tsys", isDefault = true), config.processors[1])
    }

    @Test
    fun `maps the legacy payment-config field names`() {
        val config = map(legacyBody)

        assertEquals("USD", config.currencyCode)
        assertEquals("Acme Grocery", config.merchantCompanyName)
        assertEquals(true, config.requiresPricingType)
        assertEquals(true, config.isTipsEnabled)
        assertEquals(listOf(BigDecimal("18"), BigDecimal("20"), BigDecimal("25")), config.defaultTipOptions)
        assertEquals("p-1", config.defaultProcessorId)
        assertEquals(PaymentProcessor("p-1", null, "Tsys", isDefault = true), config.processors.single())
    }

    @Test
    fun `absent optional fields map to safe defaults`() {
        val config = map("""{"zeroCostProcessingOption":"None"}""")

        assertNull(config.currencyCode)
        assertNull(config.merchantCompanyName)
        assertEquals(false, config.isTipsEnabled)
        assertEquals(emptyList<BigDecimal>(), config.defaultTipOptions)
        assertEquals(emptyList<String>(), config.processorIds)
        assertNull(config.defaultProcessorId)
    }

    @Test
    fun `cached config round-trips through Gson`() {
        val config = map(currentBody)
        assertEquals(config, gson.fromJson(gson.toJson(config), PaymentConfig::class.java))
    }
}
