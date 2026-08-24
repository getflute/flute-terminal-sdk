package com.flute.terminal.sdk.data.remote

import com.flute.terminal.sdk.model.ReadingMethod
import com.flute.terminal.sdk.model.RefundRequest
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Pins the unreferenced-refund deeplink envelope. The transaction type here is NOT a v2 API field
 * (the `/reversal` endpoint implies the type) — it is the terminal notification contract, and the
 * terminal branches on it (`Constants.TYPE_ID_NON_LINKED_REFUND = 7`). If this id were wrong or
 * missing the terminal would run its Sale flow and CHARGE the customer instead of refunding them,
 * so it is asserted explicitly.
 */
class RefundPayloadTest {

    private val gson = Gson()

    private val createResponse: JsonObject = gson.fromJson(
        """
        {
          "posTransactionId":"pos-refund-1",
          "merchantId":"merchant-1",
          "posTransactionStatus":"InProgress",
          "createdOn":"2026-07-29T10:00:00Z",
          "referenceId":"REF-1"
        }
        """.trimIndent(),
        JsonObject::class.java,
    )

    @Test
    fun `refund envelope carries the non-linked-refund type and the exact refund amount`() {
        val json = NotificationPayloadFactory.buildRefund(
            createResponse = createResponse,
            request = RefundRequest(refundAmount = BigDecimal("25.50"), posDeviceId = "POS-1"),
            terminalId = "terminal-1",
            currencyCode = "USD",
            referenceId = "REF-1",
        )
        val value = gson.fromJson(json, JsonObject::class.java).getAsJsonObject("value")

        assertEquals(7, value.get("transactionTypeId").asInt)
        assertEquals("RefundWORef", value.get("transactionType").asString)
        assertEquals(BigDecimal("25.50"), value.get("amount").asBigDecimal)
        assertEquals("pos-refund-1", value.get("posTransactionId").asString)
        assertEquals("terminal-1", value.get("terminalId").asString)

        // No ZCP math on a refund: the flat amount IS the refund, and no card/cash price choice
        // applies — so UseCardPrice must be absent rather than guessed.
        val amounts = value.getAsJsonObject("amounts")
        assertEquals(false, amounts.has("UseCardPrice"))
        assertEquals(BigDecimal("25.50"), amounts.getAsJsonObject("CreditCard").get("TotalAmount").asBigDecimal)
        assertEquals(BigDecimal("25.50"), amounts.getAsJsonObject("Cash").get("TotalAmount").asBigDecimal)
    }

    @Test
    fun `keyed-entry refund maps the terminal reading method`() {
        val json = NotificationPayloadFactory.buildRefund(
            createResponse = createResponse,
            request = RefundRequest(
                refundAmount = BigDecimal("10.00"),
                posDeviceId = "POS-1",
                readingMethod = ReadingMethod.KEYED_ENTRY,
            ),
            terminalId = "terminal-1",
            currencyCode = "USD",
            referenceId = "REF-1",
        )
        val value = gson.fromJson(json, JsonObject::class.java).getAsJsonObject("value")

        assertEquals(2, value.get("posTransactionReadingMethodId").asInt)
    }
}
