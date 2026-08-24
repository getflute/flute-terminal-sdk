package com.flute.terminal.sdk.domain.usecase

import com.flute.terminal.sdk.domain.repository.PosTransactionRepository
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.LinkedTransactionOutcome
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.PaymentResult
import com.flute.terminal.sdk.model.PosTransactionDetails
import com.flute.terminal.sdk.model.PosTransactionRef
import com.flute.terminal.sdk.model.PosTransactionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvePaymentOutcomeUseCaseTest {

    private fun useCase(details: PosTransactionDetails) = ResolvePaymentOutcomeUseCase(
        object : PosTransactionRepository {
            override suspend fun calculateAmount(
                baseAmount: java.math.BigDecimal,
                currencyCode: String,
                pricingType: String?,
                tipAmount: java.math.BigDecimal?,
                tipRate: java.math.BigDecimal?,
            ) = com.flute.terminal.sdk.model.CalculatedAmounts(currencyCode, null, null, null, null, null)

            override suspend fun create(
                request: PaymentRequest,
                terminalId: String,
                currencyCode: String,
                amounts: com.flute.terminal.sdk.model.CalculatedAmounts,
            ) = PosTransactionRef("x", null, null, "{}")

            override suspend fun createUnreferencedRefund(
                request: com.flute.terminal.sdk.model.RefundRequest,
                terminalId: String,
                currencyCode: String,
                referenceId: String,
            ) = PosTransactionRef("x", null, null, "{}")

            override suspend fun get(posTransactionId: String) = details

            override suspend fun cancel(posTransactionId: String) = details
        },
    )

    private fun details(
        status: PosTransactionStatus,
        outcome: LinkedTransactionOutcome? = null,
        transactionId: String? = "txn-1",
    ) = PosTransactionDetails("pos-1", status, transactionId, outcome)

    @Test
    fun `completed with captured linked transaction resolves Approved with hint auth code`() = runTest {
        val result = useCase(
            details(PosTransactionStatus.COMPLETED, LinkedTransactionOutcome("Captured", "00", "Approval")),
        )("pos-1", authCodeHint = "A12345")

        val approved = result as PaymentResult.Approved
        assertEquals("pos-1", approved.posTransactionId)
        assertEquals("txn-1", approved.transactionId)
        assertEquals("A12345", approved.authCode)
        assertEquals("00", approved.responseCode)
    }

    /**
     * A successful refund completes as "Refunded", which is not one of the payment approval
     * statuses — judging it by those reported money-out as DECLINED with the message "Refunded".
     */
    @Test
    fun `completed refund resolves Approved on its own Refunded status`() = runTest {
        val result = useCase(
            details(
                PosTransactionStatus.COMPLETED,
                LinkedTransactionOutcome(
                    status = "Refunded",
                    responseCode = "00",
                    responseMessage = null,
                    transactionType = "Refund",
                    processedAmount = java.math.BigDecimal("-100"),
                ),
            ),
        )("pos-1")

        val approved = result as PaymentResult.Approved
        assertEquals("Refund", approved.transactionType)
        assertEquals(java.math.BigDecimal("-100"), approved.processedAmount)
    }

    /** Type-scoped: a *sale* reading "Refunded" was refunded later, not an approved sale. */
    @Test
    fun `completed sale reading Refunded does not resolve Approved`() = runTest {
        val result = useCase(
            details(
                PosTransactionStatus.COMPLETED,
                LinkedTransactionOutcome("Refunded", "00", null, transactionType = "Sale"),
            ),
        )("pos-1")

        assertTrue(result is PaymentResult.Declined)
    }

    @Test
    fun `completed with DECLINED linked transaction resolves Declined - not Approved`() = runTest {
        val result = useCase(
            details(PosTransactionStatus.COMPLETED, LinkedTransactionOutcome("Declined", "05", "Do not honor")),
        )("pos-1")

        val declined = result as PaymentResult.Declined
        assertEquals("05", declined.responseCode)
        assertEquals("Do not honor", declined.message)
    }

    @Test
    fun `completed without linked transaction is a malformed-response error`() = runTest {
        val result = useCase(details(PosTransactionStatus.COMPLETED, outcome = null))("pos-1")
        assertEquals(ErrorReason.MALFORMED_RESPONSE, (result as PaymentResult.Error).reason)
    }

    @Test
    fun `cancelled resolves user-cancelled error`() = runTest {
        val result = useCase(details(PosTransactionStatus.CANCELLED))("pos-1")
        assertEquals(ErrorReason.USER_CANCELLED, (result as PaymentResult.Error).reason)
    }

    @Test
    fun `failed resolves terminal-failed error`() = runTest {
        val result = useCase(details(PosTransactionStatus.FAILED))("pos-1")
        assertEquals(ErrorReason.TERMINAL_FAILED, (result as PaymentResult.Error).reason)
    }

    @Test
    fun `in progress resolves to null so the caller keeps waiting`() = runTest {
        assertNull(useCase(details(PosTransactionStatus.IN_PROGRESS))("pos-1"))
    }

    @Test
    fun `concurrent resolves of the same transaction share one API fetch`() = runTest {
        var fetches = 0
        val useCase = ResolvePaymentOutcomeUseCase(
            object : PosTransactionRepository {
                override suspend fun calculateAmount(
                    baseAmount: java.math.BigDecimal,
                    currencyCode: String,
                    pricingType: String?,
                    tipAmount: java.math.BigDecimal?,
                    tipRate: java.math.BigDecimal?,
                ) = com.flute.terminal.sdk.model.CalculatedAmounts("USD", null, null, null, null, null)

                override suspend fun create(
                    request: PaymentRequest,
                    terminalId: String,
                    currencyCode: String,
                    amounts: com.flute.terminal.sdk.model.CalculatedAmounts,
                ) = PosTransactionRef("x", null, null, "{}")

                override suspend fun createUnreferencedRefund(
                    request: com.flute.terminal.sdk.model.RefundRequest,
                    terminalId: String,
                    currencyCode: String,
                    referenceId: String,
                ) = PosTransactionRef("x", null, null, "{}")

                override suspend fun get(posTransactionId: String): PosTransactionDetails {
                    fetches++
                    kotlinx.coroutines.delay(100) // hold the fetch open so callers overlap
                    return details(PosTransactionStatus.COMPLETED, LinkedTransactionOutcome("Captured", "00", null))
                }

                override suspend fun cancel(posTransactionId: String) = get(posTransactionId)
            },
        )

        // The two real-world racers: the terminal's activity result and startup recovery.
        val first = async { useCase("pos-1") }
        val second = async { useCase("pos-1") }

        assertTrue(first.await() is PaymentResult.Approved)
        assertTrue(second.await() is PaymentResult.Approved)
        assertEquals(1, fetches)

        // A later resolve of the same id (the flight is over) fetches fresh.
        assertTrue(useCase("pos-1") is PaymentResult.Approved)
        assertEquals(2, fetches)
    }

    @Test
    fun `approved statuses cover the card lifecycle`() {
        for (status in listOf("Authorized", "Captured", "Settled", "PartiallyAuthorized", "Verified", "Cleared")) {
            assertTrue(status, LinkedTransactionOutcome(status, null, null).isApproved)
        }
        for (status in listOf("Declined", "Failed", "Voided", "Refunded", "Pending")) {
            assertTrue(status, !LinkedTransactionOutcome(status, null, null).isApproved)
        }
    }
}
