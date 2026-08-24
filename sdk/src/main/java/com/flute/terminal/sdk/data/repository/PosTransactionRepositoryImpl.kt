package com.flute.terminal.sdk.data.repository

import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.mapper.Mappers
import com.flute.terminal.sdk.data.remote.FluteApi
import com.flute.terminal.sdk.data.remote.NotificationPayloadFactory
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.data.remote.dto.PosTransactionResponse
import com.flute.terminal.sdk.domain.repository.PosTransactionRepository
import com.flute.terminal.sdk.exception.FluteApiException
import com.flute.terminal.sdk.exception.PosTransactionCreationException
import com.flute.terminal.sdk.model.CalculatedAmounts
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.PosTransactionDetails
import com.flute.terminal.sdk.model.PosTransactionRef
import com.flute.terminal.sdk.model.PricingType
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.math.BigDecimal

internal class PosTransactionRepositoryImpl(
    private val api: FluteApi,
    private val tokenProvider: TokenProvider,
    private val gson: Gson,
    private val io: CoroutineDispatcher,
) : PosTransactionRepository {

    override suspend fun calculateAmount(
        baseAmount: BigDecimal,
        currencyCode: String,
        pricingType: String?,
        tipAmount: BigDecimal?,
        tipRate: BigDecimal?,
    ): CalculatedAmounts = withContext(io) {
        Mappers.toCalculatedAmounts(
            apiCall {
                api.calculateAmount(
                    tokenProvider.bearer(),
                    com.flute.terminal.sdk.data.remote.dto.CalculateAmountRequest(
                        baseAmount = baseAmount,
                        currencyCode = currencyCode,
                        pricingType = pricingType,
                        tipAmount = tipAmount,
                        tipRate = tipRate,
                    ),
                )
            },
        )
    }

    override suspend fun create(
        request: PaymentRequest,
        terminalId: String,
        currencyCode: String,
        amounts: CalculatedAmounts,
    ): PosTransactionRef = withContext(io) {
        val json = try {
            apiCall {
                api.createPosTransaction(tokenProvider.bearer(), Mappers.toCreateBody(request, terminalId, currencyCode))
            }
        } catch (e: FluteApiException) {
            throw PosTransactionCreationException(e.message ?: "Failed to create POS transaction", e, e.details)
        }
        val typed = gson.fromJson(json, PosTransactionResponse::class.java)
        val payload = NotificationPayloadFactory.build(json, request, terminalId, currencyCode, amounts)
        Mappers.toRef(typed, notificationPayloadJson = payload)
    }

    override suspend fun createUnreferencedRefund(
        request: com.flute.terminal.sdk.model.RefundRequest,
        terminalId: String,
        currencyCode: String,
        referenceId: String,
    ): PosTransactionRef = withContext(io) {
        val json = try {
            apiCall {
                api.createPosReversal(
                    tokenProvider.bearer(),
                    Mappers.toReversalBody(request, terminalId, currencyCode, referenceId),
                )
            }
        } catch (e: FluteApiException) {
            throw PosTransactionCreationException(e.message ?: "Failed to create refund", e, e.details)
        }
        val typed = gson.fromJson(json, PosTransactionResponse::class.java)
        val payload = NotificationPayloadFactory.buildRefund(json, request, terminalId, currencyCode, referenceId)
        Mappers.toRef(typed, notificationPayloadJson = payload)
    }

    override suspend fun get(posTransactionId: String): PosTransactionDetails = withContext(io) {
        Mappers.toDetails(apiCall { api.getPosTransaction(tokenProvider.bearer(), posTransactionId) })
    }

    override suspend fun cancel(posTransactionId: String): PosTransactionDetails = withContext(io) {
        Mappers.toDetails(apiCall { api.cancelPosTransaction(tokenProvider.bearer(), posTransactionId) })
    }
}
