package com.flute.terminal.sdk.data.mapper

import com.flute.terminal.sdk.data.remote.dto.LinkedTransactionDto
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The post-payment operations (capture / reversal / tip-adjustment / lookup) all return the dev v2
 * `GetTransactionResponseDto`, whose shape matches the embedded `linkedTransaction` — so one DTO and
 * one mapper serve both. These pin the fields an ISV acts on, especially the refundable remainder
 * that decides whether a further reversal is even possible.
 */
class TransactionMappingTest {

    private val gson = Gson()

    private val captured = """
        {
          "transactionId": "3cb522e9-139a-4786-a88b-1e9aa68184e0",
          "transactionStatus": "Captured",
          "transactionType": "Sale",
          "transactionDateTime": "2026-07-28T14:25:17.668087Z",
          "referenceId": "s61GnJozd1",
          "currencyCode": "USD",
          "paymentMethodType": "Card",
          "processedAmount": 100,
          "refundDetails": { "refundedAmount": 0, "availableRefundAmount": 100 },
          "amountBreakdown": { "baseAmount": 100.0, "tipAmount": 0.0, "surchargeAmount": 0, "discountAmount": 0.0 },
          "cardDetails": { "maskedCardNumber": "411111******1111", "cardBrand": "Visa", "cardProcessedAsType": "Credit" },
          "processorDetails": { "authCode": "TAS685", "rrn": "d96b5127", "mid": "888000003469", "tid": "84558505" }
        }
    """.trimIndent()

    private val fullyRefunded = """
        {
          "transactionId": "abc",
          "transactionStatus": "Refunded",
          "transactionType": "Refund",
          "originalTransactionId": "orig-1",
          "processedAmount": 50,
          "refundDetails": { "refundedAmount": 50, "availableRefundAmount": 0 }
        }
    """.trimIndent()

    @Test
    fun `captured transaction maps to an approved, still-refundable record`() {
        val t = Mappers.toTransaction(gson.fromJson(captured, LinkedTransactionDto::class.java))

        assertTrue(t.isApproved)
        assertFalse(t.isFullyRefunded)
        assertEquals("Sale", t.type)
        assertEquals("USD", t.currencyCode)
        assertEquals(BigDecimal("100"), t.processedAmount)
        assertEquals(BigDecimal("100"), t.availableRefundAmount)
        assertEquals("TAS685", t.processor?.authCode)
        assertEquals("411111******1111", t.card?.maskedPan)
    }

    @Test
    fun `fully refunded transaction reports nothing left to reverse`() {
        val t = Mappers.toTransaction(gson.fromJson(fullyRefunded, LinkedTransactionDto::class.java))

        assertTrue("a refund with no remainder must report it", t.isFullyRefunded)
        assertEquals("orig-1", t.originalTransactionId)
        assertEquals(BigDecimal("50"), t.refundedAmount)
        // A refund's approval state is its own "Refunded" status — money did move. Judging it by
        // the payment statuses reported a completed refund as declined.
        assertTrue("a completed refund is approved", t.isApproved)
    }
}
