package com.flute.terminal.sdk.data.repository

import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.mapper.Mappers
import com.flute.terminal.sdk.data.remote.FluteApi
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.data.remote.dto.CaptureRequest
import com.flute.terminal.sdk.data.remote.dto.PrintReceiptRequest
import com.flute.terminal.sdk.data.remote.dto.ReversalRequest
import com.flute.terminal.sdk.data.remote.dto.ShareReceiptRequest
import com.flute.terminal.sdk.data.remote.dto.TipAdjustmentRequest
import com.flute.terminal.sdk.domain.repository.TransactionRepository
import com.flute.terminal.sdk.model.Transaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.math.BigDecimal

internal class TransactionRepositoryImpl(
    private val api: FluteApi,
    private val tokenProvider: TokenProvider,
    private val io: CoroutineDispatcher,
) : TransactionRepository {

    override suspend fun get(transactionId: String): Transaction = withContext(io) {
        Mappers.toTransaction(apiCall { api.getTransaction(tokenProvider.bearer(), transactionId) })
    }

    override suspend fun capture(transactionId: String, amount: BigDecimal?): Transaction = withContext(io) {
        Mappers.toTransaction(
            apiCall { api.captureTransaction(tokenProvider.bearer(), transactionId, CaptureRequest(amount)) },
        )
    }

    override suspend fun reverse(transactionId: String, amount: BigDecimal?): Transaction = withContext(io) {
        Mappers.toTransaction(
            apiCall { api.reverseTransaction(tokenProvider.bearer(), transactionId, ReversalRequest(amount)) },
        )
    }

    override suspend fun adjustTip(
        transactionId: String,
        tipAmount: BigDecimal?,
        tipRate: BigDecimal?,
    ): Transaction = withContext(io) {
        Mappers.toTransaction(
            apiCall {
                api.adjustTip(tokenProvider.bearer(), transactionId, TipAdjustmentRequest(tipAmount, tipRate))
            },
        )
    }

    override suspend fun shareReceipt(
        transactionId: String,
        method: String,
        recipient: String,
        hasConsent: Boolean,
    ) {
        withContext(io) {
            apiCall {
                api.shareReceipt(
                    tokenProvider.bearer(),
                    transactionId,
                    ShareReceiptRequest(method, recipient, hasConsent),
                )
            }
        }
    }

    override suspend fun printReceipt(posTransactionId: String, terminalId: String) {
        withContext(io) {
            apiCall {
                api.printReceipt(tokenProvider.bearer(), posTransactionId, PrintReceiptRequest(terminalId))
            }
        }
    }
}
