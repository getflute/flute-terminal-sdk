package com.flute.terminal.sdk.domain.usecase

import com.flute.terminal.sdk.domain.repository.PaymentConfigRepository
import com.flute.terminal.sdk.domain.repository.PosTransactionRepository
import com.flute.terminal.sdk.domain.repository.TerminalRepository
import com.flute.terminal.sdk.model.CalculatedAmounts
import com.flute.terminal.sdk.model.PaymentConfig
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.PosTransactionRef
import com.flute.terminal.sdk.model.TerminalInfo
import java.math.BigDecimal

internal class CalculateAmountUseCase(private val repo: PosTransactionRepository) {
    suspend operator fun invoke(
        baseAmount: BigDecimal,
        currencyCode: String,
        pricingType: String?,
        tipAmount: BigDecimal?,
        tipRate: BigDecimal?,
    ): CalculatedAmounts = repo.calculateAmount(baseAmount, currencyCode, pricingType, tipAmount, tipRate)
}

internal class CreatePosTransactionUseCase(private val repo: PosTransactionRepository) {
    suspend operator fun invoke(
        request: PaymentRequest,
        terminalId: String,
        currencyCode: String,
        amounts: CalculatedAmounts,
    ): PosTransactionRef = repo.create(request, terminalId, currencyCode, amounts)
}

internal class CreateUnreferencedRefundUseCase(private val repo: PosTransactionRepository) {
    suspend operator fun invoke(
        request: com.flute.terminal.sdk.model.RefundRequest,
        terminalId: String,
        currencyCode: String,
        referenceId: String,
    ): PosTransactionRef = repo.createUnreferencedRefund(request, terminalId, currencyCode, referenceId)
}

internal class GetTerminalsUseCase(private val repo: TerminalRepository) {
    suspend operator fun invoke(): List<TerminalInfo> = repo.list()
    suspend fun bySerial(serialNumber: String): TerminalInfo? = repo.findBySerial(serialNumber)
}

internal class GetPaymentConfigUseCase(private val repo: PaymentConfigRepository) {
    suspend operator fun invoke(): PaymentConfig = repo.get()
    fun cached(): PaymentConfig? = repo.cached()
}
