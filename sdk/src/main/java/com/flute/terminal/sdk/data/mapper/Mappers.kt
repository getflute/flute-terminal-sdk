package com.flute.terminal.sdk.data.mapper

import com.flute.terminal.sdk.data.remote.dto.CalculateAmountResponse
import com.flute.terminal.sdk.data.remote.dto.CalcTenderDto
import com.flute.terminal.sdk.data.remote.dto.CreatePosTransactionRequest
import com.flute.terminal.sdk.data.remote.dto.ExtraAmounts
import com.flute.terminal.sdk.data.remote.dto.LinkedTransactionDto
import com.flute.terminal.sdk.data.remote.dto.PaymentConfigResponse
import com.flute.terminal.sdk.data.remote.dto.PosTransactionResponse
import com.flute.terminal.sdk.data.remote.dto.TerminalDto
import com.flute.terminal.sdk.model.CalculatedAmounts
import com.flute.terminal.sdk.model.CaptureMethod
import com.flute.terminal.sdk.model.TenderAmount
import com.flute.terminal.sdk.model.LinkedTransactionOutcome
import com.flute.terminal.sdk.model.PaymentConfig
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.PosTransactionDetails
import com.flute.terminal.sdk.model.PosTransactionRef
import com.flute.terminal.sdk.model.PosTransactionStatus
import com.flute.terminal.sdk.model.ReadingMethod
import com.flute.terminal.sdk.model.TerminalInfo
import com.flute.terminal.sdk.model.TerminalMode
import com.flute.terminal.sdk.model.ZeroCostOption

/** Boundary mappers: domain/public models <-> transport DTOs. The only place enums are stringified. */
internal object Mappers {

    /** See [CreatePosTransactionRequest.initiationChannel]: on-device launch, never a cloud push. */
    private const val INITIATION_CHANNEL_DEEPLINK = "Deeplink"

    fun toCreateBody(request: PaymentRequest, terminalId: String, currencyCode: String) = CreatePosTransactionRequest(
        initiationChannel = INITIATION_CHANNEL_DEEPLINK,
        posDeviceId = request.posDeviceId,
        baseAmount = request.baseAmount,
        currencyCode = currencyCode,
        terminalId = terminalId,
        captureMethod = request.captureMethod.toApi(),
        pricingType = request.pricingType?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
        readingMethod = request.readingMethod?.toApi(),
        paymentProcessorId = request.paymentProcessorId,
        customerId = request.customerId,
        referenceId = request.referenceId,
        requestPaymentMethodStorageConsent = request.requestPaymentMethodStorageConsent,
        extraAmounts = if (request.tipAmount != null || request.tipRatePercent != null) {
            ExtraAmounts(tipAmount = request.tipAmount, tipRate = request.tipRatePercent)
        } else null,
    )

    /** Unreferenced refund: no originalTransactionId, which is what starts the terminal flow. */
    fun toReversalBody(
        request: com.flute.terminal.sdk.model.RefundRequest,
        terminalId: String,
        currencyCode: String,
        referenceId: String,
    ) = com.flute.terminal.sdk.data.remote.dto.CreatePosReversalRequest(
        initiationChannel = INITIATION_CHANNEL_DEEPLINK,
        reversalAmount = request.refundAmount,
        currencyCode = currencyCode,
        terminalId = terminalId,
        referenceId = referenceId,
        posDeviceId = request.posDeviceId,
        paymentProcessorId = request.paymentProcessorId,
        customerId = request.customerId,
        readingMethod = request.readingMethod?.toApi(),
    )

    /** Standalone transaction record (operations + lookup). */
    fun toTransaction(dto: LinkedTransactionDto): com.flute.terminal.sdk.model.Transaction {
        val decline = dto.declineDetails
            ?: dto.transactionEvents?.lastOrNull { it.declineDetails != null }?.declineDetails
        val processorResponse = dto.transactionEvents
            ?.lastOrNull { it.processorResponse != null }?.processorResponse
        return com.flute.terminal.sdk.model.Transaction(
            transactionId = dto.transactionId.orEmpty(),
            status = dto.status.orEmpty(),
            type = dto.transactionType,
            processedAmount = dto.processedAmount,
            currencyCode = dto.currencyCode,
            amounts = dto.amountBreakdown?.let {
                com.flute.terminal.sdk.model.AmountBreakdown(
                    totalAmount = dto.processedAmount,
                    baseAmount = it.baseAmount,
                    tipAmount = it.tipAmount,
                    surchargeAmount = it.surchargeAmount,
                    discountAmount = it.discountAmount,
                    tipRate = it.tipRate,
                    surchargeRate = it.surchargeRate,
                    discountRate = it.discountRate,
                )
            },
            card = dto.cardDetails?.let {
                com.flute.terminal.sdk.model.CardInfo(
                    maskedPan = it.maskedCardNumber,
                    brand = it.cardBrand,
                    cardType = it.cardType,
                    processedAsType = it.cardProcessedAsType,
                    entryMode = it.cardDataSource,
                    cardholderVerificationMethod = it.cardholderVerificationMethod,
                )
            },
            processor = dto.processorDetails?.let {
                com.flute.terminal.sdk.model.ProcessorReferences(
                    authCode = it.authCode, rrn = it.rrn, mid = it.mid, tid = it.tid,
                )
            },
            avs = dto.avsResponse?.let {
                com.flute.terminal.sdk.model.AvsResult(
                    action = it.action, responseCode = it.responseCode, description = it.description,
                )
            },
            declineCode = decline?.code ?: processorResponse?.responseCode,
            declineMessage = decline?.message ?: processorResponse?.responseMessage,
            referenceId = dto.referenceId,
            transactionDateTime = dto.transactionDateTime,
            availableRefundAmount = dto.refundDetails?.availableRefundAmount,
            refundedAmount = dto.refundDetails?.refundedAmount,
            originalTransactionId = dto.originalTransactionId,
            customerId = dto.customerId,
            paymentProcessorId = dto.paymentProcessorId,
            batchId = dto.batchId,
            paymentMethodType = dto.paymentMethodType,
        )
    }

    fun toCalculatedAmounts(dto: CalculateAmountResponse) = CalculatedAmounts(
        currencyCode = dto.currencyCode,
        zeroCostOption = dto.zeroCostProcessingOption,
        cash = dto.cash?.toTender(),
        creditCard = dto.creditCard?.toTender(),
        debitCard = dto.debitCard?.toTender(),
        ach = dto.ach?.toTender(),
    )

    private fun CalcTenderDto.toTender() = TenderAmount(
        baseAmount = baseAmount,
        discountAmount = discountAmount,
        discountRate = discountRate,
        surchargeAmount = surchargeAmount,
        surchargeRate = surchargeRate,
        tipAmount = tipAmount,
        tipRate = tipRate,
        totalAmount = totalAmount,
    )

    fun toRef(dto: PosTransactionResponse, notificationPayloadJson: String) = PosTransactionRef(
        posTransactionId = dto.posTransactionId,
        terminalId = dto.terminalId,
        status = dto.posTransactionStatus,
        notificationPayloadJson = notificationPayloadJson,
    )

    fun toDetails(dto: PosTransactionResponse) = PosTransactionDetails(
        posTransactionId = dto.posTransactionId,
        status = when (dto.posTransactionStatus?.lowercase()) {
            "inprogress" -> PosTransactionStatus.IN_PROGRESS
            "completed" -> PosTransactionStatus.COMPLETED
            "cancelled" -> PosTransactionStatus.CANCELLED
            "failed" -> PosTransactionStatus.FAILED
            else -> PosTransactionStatus.UNKNOWN
        },
        transactionId = dto.transactionId ?: dto.linkedTransaction?.transactionId,
        linkedOutcome = dto.linkedTransaction?.status?.let { status ->
            val linked = dto.linkedTransaction
            // A decline carries its reason in declineDetails (code + message), on the linked
            // transaction or the final event; a legacy response used processorResponse instead.
            // Prefer the decline reason so the ISV gets the real "why" (e.g. AVS / Address
            // verification failed) rather than a bare "Declined".
            val decline = linked.declineDetails
                ?: linked.transactionEvents?.lastOrNull { it.declineDetails != null }?.declineDetails
            val processorResponse = linked.transactionEvents
                ?.lastOrNull { it.processorResponse != null }?.processorResponse
            LinkedTransactionOutcome(
                status = status,
                responseCode = decline?.code ?: processorResponse?.responseCode,
                responseMessage = decline?.message ?: processorResponse?.responseMessage,
                transactionType = linked.transactionType,
                referenceId = linked.referenceId,
                transactionDateTime = linked.transactionDateTime,
                processedAmount = linked.processedAmount,
                amounts = linked.amountBreakdown?.let {
                    com.flute.terminal.sdk.model.AmountBreakdown(
                        totalAmount = linked.processedAmount,
                        baseAmount = it.baseAmount,
                        tipAmount = it.tipAmount,
                        surchargeAmount = it.surchargeAmount,
                        discountAmount = it.discountAmount,
                        tipRate = it.tipRate,
                        surchargeRate = it.surchargeRate,
                        discountRate = it.discountRate,
                    )
                },
                card = linked.cardDetails?.let {
                    com.flute.terminal.sdk.model.CardInfo(
                        maskedPan = it.maskedCardNumber,
                        brand = it.cardBrand,
                        cardType = it.cardType,
                        processedAsType = it.cardProcessedAsType,
                        entryMode = it.cardDataSource,
                        cardholderVerificationMethod = it.cardholderVerificationMethod,
                    )
                },
                processor = linked.processorDetails?.let {
                    com.flute.terminal.sdk.model.ProcessorReferences(
                        authCode = it.authCode, rrn = it.rrn, mid = it.mid, tid = it.tid,
                    )
                },
                avs = linked.avsResponse?.let {
                    com.flute.terminal.sdk.model.AvsResult(
                        action = it.action, responseCode = it.responseCode, description = it.description,
                    )
                },
                availableRefundAmount = linked.refundDetails?.availableRefundAmount,
            )
        },
    )

    fun toInfo(dto: TerminalDto) = TerminalInfo(
        id = dto.terminalId,
        mode = when (dto.terminalMode?.lowercase()) {
            "standalone" -> TerminalMode.STANDALONE
            "semiintegrated" -> TerminalMode.SEMI_INTEGRATED
            "hybrid" -> TerminalMode.HYBRID
            else -> TerminalMode.UNKNOWN
        },
        isOnline = dto.connectionStatus?.equals("online", ignoreCase = true) == true,
        serialNumber = dto.serialNumber,
    )

    fun toConfig(dto: PaymentConfigResponse) = PaymentConfig(
        currencyCode = dto.currency,
        zeroCostOption = when (dto.zeroCostProcessingOption?.lowercase()) {
            "none" -> ZeroCostOption.NONE
            "cashdiscount" -> ZeroCostOption.CASH_DISCOUNT
            "dualpricing" -> ZeroCostOption.DUAL_PRICING
            "surcharge" -> ZeroCostOption.SURCHARGE
            else -> ZeroCostOption.UNKNOWN
        },
        defaultProcessorId = dto.availablePaymentProcessors.firstOrNull { it.isDefault }?.paymentProcessorId,
        processorIds = dto.availablePaymentProcessors.map { it.paymentProcessorId },
    )

    private fun CaptureMethod.toApi() = when (this) {
        CaptureMethod.AUTO -> "Auto"
        CaptureMethod.MANUAL -> "Manual"
    }

    private fun ReadingMethod.toApi() = when (this) {
        ReadingMethod.REGULAR -> "Regular"
        ReadingMethod.KEYED_ENTRY -> "KeyedEntry"
    }
}
