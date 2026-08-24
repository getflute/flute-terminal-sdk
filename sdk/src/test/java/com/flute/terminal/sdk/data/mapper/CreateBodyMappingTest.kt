package com.flute.terminal.sdk.data.mapper

import com.flute.terminal.sdk.model.CaptureMethod
import com.flute.terminal.sdk.model.PaymentRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Every transaction this SDK creates is picked up by the terminal app on this same device, never
 * pushed from the cloud. `initiationChannel` is what tells the backend that: it skips the websocket
 * push and the Online/Ready terminal gates, which otherwise reject a payment on a terminal whose
 * reported connection status is stale — the common case for a mobile terminal.
 */
class CreateBodyMappingTest {

    private val gson = Gson()

    @Test
    fun `create body declares the deeplink initiation channel`() {
        val body = Mappers.toCreateBody(
            request = PaymentRequest.Builder(BigDecimal("12.34")).build(),
            terminalId = "term-1",
            currencyCode = "USD",
        )

        val json = JsonParser.parseString(gson.toJson(body)).asJsonObject

        assertEquals("Deeplink", json["initiationChannel"].asString)
    }

    /**
     * Refunds run the same on-device flow, but the backend's reversal contract defaults the channel
     * to Cloud — so omitting it made every SDK refund a cloud transaction: rejected while the
     * terminal was Busy, and pushed over the websocket on top of the deeplink launch.
     */
    @Test
    fun `reversal body declares the deeplink initiation channel`() {
        val body = Mappers.toReversalBody(
            request = com.flute.terminal.sdk.model.RefundRequest(
                refundAmount = BigDecimal("12.34"),
                posDeviceId = "pos-1",
            ),
            terminalId = "term-1",
            currencyCode = "USD",
            referenceId = "refund-1",
        )

        val json = JsonParser.parseString(gson.toJson(body)).asJsonObject

        assertEquals("Deeplink", json["initiationChannel"].asString)
    }

    @Test
    fun `create body omits waitForAcceptanceByTerminal, which deeplink rejects`() {
        val body = Mappers.toCreateBody(
            request = PaymentRequest.Builder(BigDecimal("12.34"))
                .captureMethod(CaptureMethod.AUTO)
                .build(),
            terminalId = "term-1",
            currencyCode = "USD",
        )

        val json = JsonParser.parseString(gson.toJson(body)).asJsonObject

        assertEquals(false, json.has("waitForAcceptanceByTerminal"))
    }
}
