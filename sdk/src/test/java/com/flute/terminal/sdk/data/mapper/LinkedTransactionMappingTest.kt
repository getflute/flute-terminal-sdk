package com.flute.terminal.sdk.data.mapper

import com.flute.terminal.sdk.data.remote.dto.PosTransactionResponse
import com.flute.terminal.sdk.model.PosTransactionStatus
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire contract for `GET /v2/pos/transactions/{id}`. A live UAT response that was
 * genuinely APPROVED/Captured was being mapped to a MALFORMED_RESPONSE error because the linked
 * transaction's status field had been renamed `status` → `transactionStatus`. These parse the
 * real bodies so either field name resolves to an approved outcome.
 */
class LinkedTransactionMappingTest {

    private val gson = Gson()

    /** Current schema: `transactionStatus` (the shape that regressed). */
    private val newSchemaCompletedApproved = """
        {
          "posTransactionId":"3ffd4b86-e3e9-491d-8e97-8f44e9d4a44f",
          "posTransactionStatus":"Completed",
          "transactionId":"2664c4df-5ac6-478a-93b9-3e04ed74d9e2",
          "linkedTransaction":{
            "transactionId":"2664c4df-5ac6-478a-93b9-3e04ed74d9e2",
            "transactionStatus":"Captured",
            "transactionType":"Sale",
            "processedAmount":12.71,
            "transactionEvents":[{"type":"Sale","status":"Approved","amount":12.71}]
          }
        }
    """.trimIndent()

    /** Legacy schema: `status` (must still work — alternate). */
    private val legacySchemaCompletedApproved = """
        {
          "posTransactionId":"abc",
          "posTransactionStatus":"Completed",
          "transactionId":"txn-legacy",
          "linkedTransaction":{
            "transactionId":"txn-legacy",
            "status":"Captured",
            "transactionEvents":[{"status":"Approved","processorResponse":{"responseCode":"00","responseMessage":"APPROVAL"}}]
          }
        }
    """.trimIndent()

    /** Real captured body (live UAT): the full receipt-grade record must flow to the ISV. */
    private val capturedWithFullRecord = """
        {
          "posTransactionId":"ab577ca8-7983-40d3-b2d4-863fe069e110",
          "posTransactionStatus":"Completed",
          "transactionId":"3cb522e9-139a-4786-a88b-1e9aa68184e0",
          "linkedTransaction":{
            "transactionId":"3cb522e9-139a-4786-a88b-1e9aa68184e0",
            "transactionDateTime":"2026-07-28T14:25:17.668087Z",
            "transactionStatus":"Captured",
            "transactionType":"Sale",
            "referenceId":"s61GnJozd1",
            "processedAmount":100,
            "refundDetails":{"refundedAmount":0,"availableRefundAmount":100},
            "amountBreakdown":{"baseAmount":100.0,"tipAmount":0.0,"surchargeAmount":0,"discountAmount":0.0,"tipRate":0,"discountRate":0,"surchargeRate":0},
            "cardDetails":{"maskedCardNumber":"411111******1111","cardBrand":"Visa","cardType":"Debit","cardProcessedAsType":"Credit","cardDataSource":"Manual","cardholderVerificationMethod":"ManualSignature"},
            "processorDetails":{"mid":"888000003469","tid":"84558505","authCode":"TAS685","rrn":"d96b512747984451a332206545b81193"},
            "addressVerificationServiceResponse":{"action":"Allow","responseCode":"U","description":"No response from issuer platform."},
            "transactionEvents":[{"type":"Sale","status":"Approved","amount":100.0}]
          }
        }
    """.trimIndent()

    @Test
    fun `full receipt-grade record flows through - amounts, card, processor refs, refundable`() {
        val details = Mappers.toDetails(gson.fromJson(capturedWithFullRecord, PosTransactionResponse::class.java))
        val outcome = details.linkedOutcome!!

        assertTrue(outcome.isApproved)
        assertEquals("TAS685", outcome.processor?.authCode)
        assertEquals("d96b512747984451a332206545b81193", outcome.processor?.rrn)
        assertEquals(java.math.BigDecimal("100"), outcome.processedAmount)
        assertEquals(java.math.BigDecimal("100"), outcome.availableRefundAmount)
        assertEquals("411111******1111", outcome.card?.maskedPan)
        assertEquals("Visa", outcome.card?.brand)
        assertEquals("Credit", outcome.card?.processedAsType)
        assertEquals("Manual", outcome.card?.entryMode)
        assertEquals(java.math.BigDecimal("100.0"), outcome.amounts?.baseAmount)
        assertEquals("U", outcome.avs?.responseCode)
        assertEquals("s61GnJozd1", outcome.referenceId)
        assertEquals("Sale", outcome.transactionType)
    }

    @Test
    fun `current schema (transactionStatus) maps a captured sale to an approved outcome`() {
        val details = Mappers.toDetails(gson.fromJson(newSchemaCompletedApproved, PosTransactionResponse::class.java))

        assertEquals(PosTransactionStatus.COMPLETED, details.status)
        assertEquals("2664c4df-5ac6-478a-93b9-3e04ed74d9e2", details.transactionId)
        assertTrue("captured must be approved", details.linkedOutcome?.isApproved == true)
    }

    /** Real declined body: reason lives in declineDetails, not processorResponse. */
    private val declinedWithAvsDetails = """
        {
          "posTransactionId":"c4bf9786",
          "posTransactionStatus":"Completed",
          "transactionId":"6c095c57",
          "linkedTransaction":{
            "transactionId":"6c095c57",
            "transactionStatus":"Declined",
            "declineDetails":{"code":"AVS","message":"Address verification failed"},
            "transactionEvents":[{"type":"Sale","status":"Declined","declineDetails":{"code":"AVS","message":"Address verification failed"}}]
          }
        }
    """.trimIndent()

    @Test
    fun `declined outcome carries the real decline reason (code and message), not a bare Declined`() {
        val details = Mappers.toDetails(gson.fromJson(declinedWithAvsDetails, PosTransactionResponse::class.java))

        assertEquals(PosTransactionStatus.COMPLETED, details.status)
        val outcome = details.linkedOutcome!!
        assertEquals("declined must not read as approved", false, outcome.isApproved)
        assertEquals("AVS", outcome.responseCode)
        assertEquals("Address verification failed", outcome.responseMessage)
    }

    @Test
    fun `legacy schema (status) still maps to an approved outcome with response code`() {
        val details = Mappers.toDetails(gson.fromJson(legacySchemaCompletedApproved, PosTransactionResponse::class.java))

        assertEquals(PosTransactionStatus.COMPLETED, details.status)
        assertTrue(details.linkedOutcome?.isApproved == true)
        assertEquals("00", details.linkedOutcome?.responseCode)
    }
}
