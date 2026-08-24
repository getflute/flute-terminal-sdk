package com.flute.terminal.sdk.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** Response from `POST /oauth2/token` (client_credentials grant). */
internal data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresInSeconds: Long,
)

/**
 * Body for `POST /v2/pos/transactions`, mirroring IsvApiBff v2 `CreatePosTransactionRequestDto`.
 * `merchantId` is intentionally absent — taken from the bearer token.
 */
internal data class CreatePosTransactionRequest(
    @SerializedName("posDeviceId") val posDeviceId: String? = null,
    @SerializedName("baseAmount") val baseAmount: BigDecimal,
    @SerializedName("currencyCode") val currencyCode: String,
    @SerializedName("terminalId") val terminalId: String,
    @SerializedName("captureMethod") val captureMethod: String,
    @SerializedName("pricingType") val pricingType: String? = null,
    @SerializedName("readingMethod") val readingMethod: String? = null,
    @SerializedName("paymentProcessorId") val paymentProcessorId: String? = null,
    @SerializedName("customerId") val customerId: String? = null,
    @SerializedName("referenceId") val referenceId: String? = null,
    @SerializedName("requestPaymentMethodStorageConsent") val requestPaymentMethodStorageConsent: Boolean = false,
    @SerializedName("extraAmounts") val extraAmounts: ExtraAmounts? = null,
    /**
     * How the transaction reaches the terminal. Always `Deeplink` for this SDK: the terminal app is
     * launched on this same device, so there is nothing to push and the backend's Online/Ready
     * connection gates — which reject payments on terminals whose reported websocket status is
     * stale — do not apply.
     */
    @SerializedName("initiationChannel") val initiationChannel: String,
)

/**
 * `POST /v2/pos/transactions/reversal` request. The SDK only issues the **unreferenced refund**
 * form — [originalTransactionId] is deliberately never set, which is what makes the backend start a
 * refund flow on the terminal (the customer presents their card). Supplying it would instead select
 * a void / referenced refund, which are cloud operations and out of this SDK's scope.
 */
internal data class CreatePosReversalRequest(
    /**
     * Required, exactly as on a payment: the backend defaults this to Cloud, which pushes the
     * transaction over the websocket and enforces the terminal-online/ready check at create time.
     * A deeplink refund is launched on-device instead, so omitting it both rejected refunds while
     * the terminal was Busy and risked the terminal receiving the same job twice.
     */
    @SerializedName("initiationChannel") val initiationChannel: String,
    @SerializedName("reversalAmount") val reversalAmount: java.math.BigDecimal,
    @SerializedName("currencyCode") val currencyCode: String,
    @SerializedName("terminalId") val terminalId: String,
    @SerializedName("referenceId") val referenceId: String,
    @SerializedName("posDeviceId") val posDeviceId: String,
    @SerializedName("paymentProcessorId") val paymentProcessorId: String? = null,
    @SerializedName("customerId") val customerId: String? = null,
    @SerializedName("readingMethod") val readingMethod: String? = null,
    /**
     * false = short polling: respond immediately and let the SDK drive the terminal via deeplink and
     * resolve the outcome itself, mirroring the payment flow.
     */
    @SerializedName("waitForAcceptanceByTerminal") val waitForAcceptanceByTerminal: Boolean = false,
)

internal data class ExtraAmounts(
    @SerializedName("tipAmount") val tipAmount: BigDecimal? = null,
    @SerializedName("tipRate") val tipRate: BigDecimal? = null,
)

internal data class PosTransactionResponse(
    @SerializedName("posTransactionId") val posTransactionId: String,
    @SerializedName("terminalId") val terminalId: String?,
    @SerializedName("posTransactionStatus") val posTransactionStatus: String?,
    /** Gateway transaction id; null until posTransactionStatus is Completed. */
    @SerializedName("transactionId") val transactionId: String?,
    /** Full payment record; null until Completed. Only the outcome subset is modeled. */
    @SerializedName("linkedTransaction") val linkedTransaction: LinkedTransactionDto?,
)

internal data class LinkedTransactionDto(
    @SerializedName("transactionId") val transactionId: String?,
    /**
     * Aggregated transaction status, e.g. "Captured", "Declined". The API field was renamed
     * `status` → `transactionStatus`; `alternate` keeps the SDK working against both so a
     * schema roll on either side can't silently turn an approval into a MALFORMED_RESPONSE.
     */
    @SerializedName(value = "transactionStatus", alternate = ["status"]) val status: String?,
    @SerializedName("transactionType") val transactionType: String? = null,
    @SerializedName("transactionDateTime") val transactionDateTime: String? = null,
    @SerializedName("referenceId") val referenceId: String? = null,
    @SerializedName("processedAmount") val processedAmount: java.math.BigDecimal? = null,
    @SerializedName("amountBreakdown") val amountBreakdown: AmountBreakdownDto? = null,
    @SerializedName("cardDetails") val cardDetails: CardDetailsDto? = null,
    @SerializedName("processorDetails") val processorDetails: ProcessorDetailsDto? = null,
    @SerializedName("refundDetails") val refundDetails: RefundDetailsDto? = null,
    @SerializedName("currencyCode") val currencyCode: String? = null,
    @SerializedName("originalTransactionId") val originalTransactionId: String? = null,
    @SerializedName("customerId") val customerId: String? = null,
    @SerializedName("paymentProcessorId") val paymentProcessorId: String? = null,
    @SerializedName("batchId") val batchId: String? = null,
    @SerializedName("paymentMethodType") val paymentMethodType: String? = null,
    @SerializedName("addressVerificationServiceResponse") val avsResponse: AvsResponseDto? = null,
    /** Present on a decline — the human-facing reason (e.g. code "AVS"). */
    @SerializedName("declineDetails") val declineDetails: DeclineDetailsDto?,
    @SerializedName("transactionEvents") val transactionEvents: List<TransactionEventDto>?,
)

internal data class AmountBreakdownDto(
    @SerializedName("baseAmount") val baseAmount: java.math.BigDecimal? = null,
    @SerializedName("tipAmount") val tipAmount: java.math.BigDecimal? = null,
    @SerializedName("surchargeAmount") val surchargeAmount: java.math.BigDecimal? = null,
    @SerializedName("discountAmount") val discountAmount: java.math.BigDecimal? = null,
    @SerializedName("tipRate") val tipRate: java.math.BigDecimal? = null,
    @SerializedName("surchargeRate") val surchargeRate: java.math.BigDecimal? = null,
    @SerializedName("discountRate") val discountRate: java.math.BigDecimal? = null,
)

internal data class CardDetailsDto(
    @SerializedName("maskedCardNumber") val maskedCardNumber: String? = null,
    @SerializedName("cardBrand") val cardBrand: String? = null,
    @SerializedName("cardType") val cardType: String? = null,
    @SerializedName("cardProcessedAsType") val cardProcessedAsType: String? = null,
    @SerializedName("cardDataSource") val cardDataSource: String? = null,
    @SerializedName("cardholderVerificationMethod") val cardholderVerificationMethod: String? = null,
)

internal data class ProcessorDetailsDto(
    @SerializedName("authCode") val authCode: String? = null,
    @SerializedName("rrn") val rrn: String? = null,
    @SerializedName("mid") val mid: String? = null,
    @SerializedName("tid") val tid: String? = null,
)

internal data class RefundDetailsDto(
    @SerializedName("refundedAmount") val refundedAmount: java.math.BigDecimal? = null,
    @SerializedName("availableRefundAmount") val availableRefundAmount: java.math.BigDecimal? = null,
)

internal data class AvsResponseDto(
    @SerializedName("action") val action: String? = null,
    @SerializedName("responseCode") val responseCode: String? = null,
    @SerializedName("description") val description: String? = null,
)

internal data class TransactionEventDto(
    @SerializedName("status") val status: String?,
    @SerializedName("declineDetails") val declineDetails: DeclineDetailsDto?,
    @SerializedName("processorResponse") val processorResponse: ProcessorResponseDto?,
)

/** Decline reason on the linked transaction / event, e.g. {"code":"AVS","message":"Address verification failed"}. */
internal data class DeclineDetailsDto(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
)

internal data class ProcessorResponseDto(
    @SerializedName("responseCode") val responseCode: String?,
    @SerializedName("responseMessage") val responseMessage: String?,
)

internal data class TerminalListResponse(
    @SerializedName("items") val items: List<TerminalDto> = emptyList(),
)

internal data class TerminalDto(
    @SerializedName("terminalId") val terminalId: String,
    @SerializedName("terminalMode") val terminalMode: String?,
    @SerializedName("connectionStatus") val connectionStatus: String?,
    @SerializedName("serialNumber") val serialNumber: String? = null,
)

internal data class PaymentConfigResponse(
    @SerializedName("currency") val currency: String?,
    @SerializedName("zeroCostProcessingOption") val zeroCostProcessingOption: String?,
    @SerializedName("availablePaymentProcessors") val availablePaymentProcessors: List<ProcessorDto> = emptyList(),
)

internal data class ProcessorDto(
    @SerializedName("paymentProcessorId") val paymentProcessorId: String,
    @SerializedName("isDefault") val isDefault: Boolean = false,
)

/** `GET /v2/transactions/calculate-amount` — gateway-computed per-tender totals. */
internal data class CalculateAmountResponse(
    @SerializedName("currencyCode") val currencyCode: String?,
    /** Merchant ZCP mode echoed back: None | CashDiscount | DualPricing | Surcharge. */
    @SerializedName("zeroCostProcessingOption") val zeroCostProcessingOption: String?,
    @SerializedName("pricingType") val pricingType: String?,
    @SerializedName("cash") val cash: CalcTenderDto?,
    @SerializedName("creditCard") val creditCard: CalcTenderDto?,
    @SerializedName("debitCard") val debitCard: CalcTenderDto?,
    @SerializedName("ach") val ach: CalcTenderDto?,
)

/**
 * `POST /v2/transactions/calculate-amount` request body (nulls omitted by Gson). Full endpoint
 * contract. The payment flow populates only the fields the create endpoint can also honor
 * (pricingType + tips): sending [discountAmount]/[discountRate]/[surchargeRate] there would make
 * the terminal DISPLAY totals the actual charge cannot match — create has no such inputs.
 */
internal data class CalculateAmountRequest(
    @SerializedName("baseAmount") val baseAmount: java.math.BigDecimal,
    @SerializedName("currencyCode") val currencyCode: String,
    /** Dual-Pricing merchants only: which price [baseAmount] is — "Card" or "Cash". */
    @SerializedName("pricingType") val pricingType: String? = null,
    /** Fixed discount; converted server-side to a rate against baseAmount. Rate wins if both set. */
    @SerializedName("discountAmount") val discountAmount: java.math.BigDecimal? = null,
    /** Percentage-off rate, raw percent (10 = 10%). */
    @SerializedName("discountRate") val discountRate: java.math.BigDecimal? = null,
    /** Surcharge-rate override, raw percent; null = merchant default. */
    @SerializedName("surchargeRate") val surchargeRate: java.math.BigDecimal? = null,
    @SerializedName("tipAmount") val tipAmount: java.math.BigDecimal? = null,
    @SerializedName("tipRate") val tipRate: java.math.BigDecimal? = null,
)

/**
 * The public calculate-amount projection: it collapses percentage-off + cash discount into a single
 * [discountAmount]/[discountRate] and omits tax. Full ZCP fidelity (separate percentage-off vs cash
 * discount, tax) needs the backend to return the notification envelope directly — see ARISE-4420.
 */
internal data class CalcTenderDto(
    @SerializedName("baseAmount") val baseAmount: java.math.BigDecimal? = null,
    @SerializedName("discountAmount") val discountAmount: java.math.BigDecimal? = null,
    @SerializedName("discountRate") val discountRate: java.math.BigDecimal? = null,
    @SerializedName("surchargeAmount") val surchargeAmount: java.math.BigDecimal? = null,
    @SerializedName("surchargeRate") val surchargeRate: java.math.BigDecimal? = null,
    @SerializedName("tipAmount") val tipAmount: java.math.BigDecimal? = null,
    @SerializedName("tipRate") val tipRate: java.math.BigDecimal? = null,
    @SerializedName("totalAmount") val totalAmount: java.math.BigDecimal? = null,
)

/** `POST /v2/transactions/{id}/capture` — omit the amount to capture the full authorization. */
internal data class CaptureRequest(
    @SerializedName("captureAmount") val captureAmount: java.math.BigDecimal? = null,
)

/** `POST /v2/transactions/{id}/reversal` — omit the amount for a full void/refund. */
internal data class ReversalRequest(
    @SerializedName("reversalAmount") val reversalAmount: java.math.BigDecimal? = null,
)

/** `POST /v2/transactions/{id}/tip-adjustment` — supply an amount OR a rate. */
internal data class TipAdjustmentRequest(
    @SerializedName("tipAmount") val tipAmount: java.math.BigDecimal? = null,
    @SerializedName("tipRate") val tipRate: java.math.BigDecimal? = null,
)

/** `POST /v2/transactions/{id}/share-receipt`. */
internal data class ShareReceiptRequest(
    @SerializedName("shareBy") val shareBy: String,
    @SerializedName("recipient") val recipient: String,
    @SerializedName("hasCustomerConsent") val hasCustomerConsent: Boolean,
)

/** `POST /v2/pos/transactions/{id}/print-receipt` — the terminal that should print. */
internal data class PrintReceiptRequest(
    @SerializedName("terminalId") val terminalId: String,
)
