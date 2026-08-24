package com.flute.terminal.sdk.domain.usecase

import com.flute.terminal.sdk.domain.repository.PosTransactionRepository
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.PaymentResult
import com.flute.terminal.sdk.model.PosTransactionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves the **canonical** payment outcome from the API record (source of truth — the Intent
 * returned by the terminal app is only a hint).
 *
 * Encodes the two-level status rule: a POS transaction reaching `Completed` is not approval —
 * the payment outcome lives on the linked transaction, which may be a decline.
 *
 * **Single-flight per transaction:** two triggers can legitimately race — the terminal's activity
 * result and startup recovery (`checkPendingPayment`) both resolve when the ISV activity was
 * recreated behind the terminal app. Concurrent calls for the same id share one API fetch instead
 * of issuing duplicate GETs.
 *
 * Returns null while the POS transaction is still in progress (caller decides to wait/poll).
 */
internal class ResolvePaymentOutcomeUseCase(private val repo: PosTransactionRepository) {

    private val inFlightLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<PaymentResult?>>()

    suspend operator fun invoke(posTransactionId: String, authCodeHint: String? = null): PaymentResult? {
        val (deferred, owner) = inFlightLock.withLock {
            inFlight[posTransactionId]?.let { it to false }
                ?: CompletableDeferred<PaymentResult?>().also { inFlight[posTransactionId] = it } to true
        }
        if (!owner) return deferred.await()

        try {
            val result = resolve(posTransactionId, authCodeHint)
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlightLock.withLock { inFlight.remove(posTransactionId) }
        }
    }

    private suspend fun resolve(posTransactionId: String, authCodeHint: String?): PaymentResult? {
        val details = repo.get(posTransactionId)
        return when (details.status) {
            PosTransactionStatus.IN_PROGRESS -> null

            PosTransactionStatus.COMPLETED -> {
                val outcome = details.linkedOutcome
                when {
                    outcome == null -> PaymentResult.Error(
                        ErrorReason.MALFORMED_RESPONSE,
                        "POS transaction completed but no linked transaction was returned.",
                        posTransactionId,
                    )

                    outcome.isApproved -> PaymentResult.Approved(
                        posTransactionId = posTransactionId,
                        transactionId = details.transactionId,
                        // Canonical record first (processorDetails.authCode); the terminal app's
                        // Intent hint covers older records that lacked it.
                        authCode = outcome.processor?.authCode ?: authCodeHint,
                        responseCode = outcome.responseCode,
                        receiptData = null,
                        processedAmount = outcome.processedAmount,
                        amounts = outcome.amounts,
                        card = outcome.card,
                        processor = outcome.processor,
                        avs = outcome.avs,
                        gatewayReferenceId = outcome.referenceId,
                        transactionDateTime = outcome.transactionDateTime,
                        availableRefundAmount = outcome.availableRefundAmount,
                        transactionType = outcome.transactionType,
                    )

                    else -> PaymentResult.Declined(
                        posTransactionId = posTransactionId,
                        transactionId = details.transactionId,
                        responseCode = outcome.responseCode,
                        message = outcome.responseMessage ?: outcome.status,
                        processedAmount = outcome.processedAmount,
                        amounts = outcome.amounts,
                        card = outcome.card,
                        avs = outcome.avs,
                        gatewayReferenceId = outcome.referenceId,
                        transactionDateTime = outcome.transactionDateTime,
                    )
                }
            }

            PosTransactionStatus.CANCELLED -> PaymentResult.Error(
                ErrorReason.USER_CANCELLED,
                "Payment was cancelled.",
                posTransactionId,
            )

            PosTransactionStatus.FAILED -> PaymentResult.Error(
                ErrorReason.TERMINAL_FAILED,
                "The terminal payment flow failed.",
                posTransactionId,
            )

            PosTransactionStatus.UNKNOWN -> PaymentResult.Error(
                ErrorReason.MALFORMED_RESPONSE,
                "Unrecognized POS transaction status.",
                posTransactionId,
            )
        }
    }
}
