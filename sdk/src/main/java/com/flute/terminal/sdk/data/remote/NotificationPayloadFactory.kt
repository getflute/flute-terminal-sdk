package com.flute.terminal.sdk.data.remote

import com.flute.terminal.sdk.model.CalculatedAmounts
import com.flute.terminal.sdk.model.CaptureMethod
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.ReadingMethod
import com.flute.terminal.sdk.model.TenderAmount
import com.google.gson.JsonObject
import java.math.BigDecimal

/**
 * Shapes the create result into the **POS-transaction notification envelope** the Flute Terminal app
 * consumes (`PosTransactionTerminalNotification` → its `PosTransaction`/`Value` model), so the
 * terminal drives its existing flow unchanged from a deeplink.
 *
 * Money amounts come from the gateway's `calculate-amount` ([CalculatedAmounts]) — the SDK does not
 * compute ZCP/surcharge/tip math itself. INTERIM: the public calculate-amount projection lacks the
 * rate fields the terminal's internal `Amounts` carries, so full ZCP fidelity still wants the
 * backend to return the envelope directly (ARISE-4420). For None-ZCP this is exact.
 */
internal object NotificationPayloadFactory {

    const val NOTIFICATION_TYPE = "PosTransactionTerminalNotification"
    private const val TYPE_ID_AUTHORIZATION = 1
    private const val TYPE_ID_SALE = 2
    /** Unreferenced refund ("return without reference") — id 7 per the terminal's transaction types. */
    private const val TYPE_ID_REFUND_WO_REF = 7
    private const val READING_REGULAR = 1
    private const val READING_KEYED = 2

    // Verified from the live UAT notification envelope: USD -> CurrencyId 1, None ZCP -> id 1.
    private fun currencyId(code: String) = if (code.equals("USD", true)) 1 else 0

    fun build(
        createResponse: JsonObject,
        request: PaymentRequest,
        terminalId: String,
        currencyCode: String,
        amounts: CalculatedAmounts,
    ): String {
        fun str(field: String): String =
            createResponse.get(field)?.takeIf { !it.isJsonNull }?.asString ?: ""

        val isSale = request.captureMethod == CaptureMethod.AUTO
        val curId = currencyId(currencyCode)

        val value = JsonObject().apply {
            addProperty("amount", request.baseAmount)
            addProperty("merchantId", str("merchantId"))
            addProperty("customerId", request.customerId ?: str("customerId"))
            addProperty("paymentProcessorId", request.paymentProcessorId ?: str("paymentProcessorId"))
            addProperty("posTransactionId", str("posTransactionId"))
            addProperty("posTransactionStatus", str("posTransactionStatus"))
            addProperty("notificationSource", "PosTransactionCreated")
            addProperty("notificationSourceId", 1)
            addProperty("referenceId", request.referenceId ?: str("referenceId"))
            addProperty("terminalId", terminalId)
            addProperty("transactionType", if (isSale) "Sale" else "Authorization")
            addProperty("transactionTypeId", if (isSale) TYPE_ID_SALE else TYPE_ID_AUTHORIZATION)
            addProperty("currencyId", curId)
            addProperty("requestPaymentMethodStorageConsent", request.requestPaymentMethodStorageConsent)
            request.readingMethod?.let {
                addProperty("posTransactionReadingMethodId", if (it == ReadingMethod.KEYED_ENTRY) READING_KEYED else READING_REGULAR)
            }
            request.tipAmount?.let { addProperty("tipAmount", it) }
            request.tipRatePercent?.let { addProperty("tipRate", it) }
            // UseCardPrice is a merchant/ISV pricing decision (which price the base amount is),
            // supplied per transaction; Card -> true, Cash -> false, None-ZCP -> null.
            val useCardPrice = when (request.pricingType) {
                com.flute.terminal.sdk.model.PricingType.CARD -> true
                com.flute.terminal.sdk.model.PricingType.CASH -> false
                null -> null
            }
            add("amounts", amountsJson(amounts, curId, currencyCode, useCardPrice))
        }

        return envelope(terminalId, str("createdOn"), value)
    }

    /**
     * Unreferenced-refund envelope (`transactionTypeId` 7). No ZCP/pricing inputs: the ISV states
     * the exact amount to return, so the flat amount IS the refund — there is no card-vs-cash price
     * to disambiguate and the backend applies no ZCP math to a refund.
     */
    fun buildRefund(
        createResponse: JsonObject,
        request: com.flute.terminal.sdk.model.RefundRequest,
        terminalId: String,
        currencyCode: String,
        referenceId: String,
    ): String {
        fun str(field: String): String =
            createResponse.get(field)?.takeIf { !it.isJsonNull }?.asString ?: ""

        val curId = currencyId(currencyCode)
        val amounts = CalculatedAmounts.flat(request.refundAmount, currencyCode)

        val value = JsonObject().apply {
            addProperty("amount", request.refundAmount)
            addProperty("merchantId", str("merchantId"))
            addProperty("customerId", request.customerId ?: str("customerId"))
            addProperty("paymentProcessorId", request.paymentProcessorId ?: str("paymentProcessorId"))
            addProperty("posTransactionId", str("posTransactionId"))
            addProperty("posTransactionStatus", str("posTransactionStatus"))
            addProperty("notificationSource", "PosTransactionCreated")
            addProperty("notificationSourceId", 1)
            addProperty("referenceId", referenceId)
            addProperty("terminalId", terminalId)
            addProperty("transactionType", "RefundWORef")
            addProperty("transactionTypeId", TYPE_ID_REFUND_WO_REF)
            addProperty("currencyId", curId)
            addProperty("requestPaymentMethodStorageConsent", false)
            request.readingMethod?.let {
                addProperty("posTransactionReadingMethodId", if (it == ReadingMethod.KEYED_ENTRY) READING_KEYED else READING_REGULAR)
            }
            add("amounts", amountsJson(amounts, curId, currencyCode, useCardPrice = null))
        }

        return envelope(terminalId, str("createdOn"), value)
    }

    private fun envelope(terminalId: String, createdAt: String, value: JsonObject): String =
        JsonObject().apply {
            addProperty("correlationId", "")
            addProperty("notificationType", NOTIFICATION_TYPE)
            addProperty("terminalId", terminalId)
            addProperty("createdAt", createdAt)
            add("value", value)
        }.toString()

    // ZCP option name -> id, matching the backend enum (None=1, CashDiscount=2, DualPricing=3, Surcharge=4).
    private fun zcpId(option: String?): Int = when (option?.lowercase()) {
        "cashdiscount" -> 2
        "dualpricing" -> 3
        "surcharge" -> 4
        else -> 1 // None / unknown
    }

    /**
     * Builds the terminal's `Amounts` block from the calculate-amount projection. The public
     * projection collapses percentage-off + cash discount into one discount figure and omits tax,
     * so PercentageOff* and Tax* are emitted as 0 and the combined discount is placed on
     * CashDiscount* (exact for pure cash-discount/dual-pricing/surcharge merchants; a percentage-off
     * promo would read as a cash discount). Full fidelity awaits ARISE-4420.
     */
    private fun amountsJson(
        amounts: CalculatedAmounts,
        curId: Int,
        currencyCode: String,
        useCardPrice: Boolean?,
    ): JsonObject {
        fun tender(t: TenderAmount?): JsonObject = JsonObject().apply {
            val zero = BigDecimal.ZERO
            addProperty("BaseAmount", t?.baseAmount ?: zero)
            addProperty("PercentageOffAmount", zero)
            addProperty("PercentageOffRate", zero)
            addProperty("CashDiscountAmount", t?.discountAmount ?: zero)
            addProperty("CashDiscountRate", t?.discountRate ?: zero)
            addProperty("SurchargeAmount", t?.surchargeAmount ?: zero)
            addProperty("SurchargeRate", t?.surchargeRate ?: zero)
            addProperty("TipAmount", t?.tipAmount ?: zero)
            addProperty("TipRate", t?.tipRate ?: zero)
            addProperty("TaxAmount", zero)
            addProperty("TaxRate", zero)
            addProperty("TotalAmount", t?.totalAmount ?: t?.baseAmount ?: zero)
        }
        return JsonObject().apply {
            addProperty("CurrencyId", curId)
            addProperty("Currency", currencyCode)
            addProperty("ZeroCostProcessingOptionId", zcpId(amounts.zeroCostOption))
            addProperty("ZeroCostProcessingOption", amounts.zeroCostOption ?: "None")
            if (useCardPrice != null) addProperty("UseCardPrice", useCardPrice)
            add("Cash", tender(amounts.cash))
            add("CreditCard", tender(amounts.creditCard))
            add("DebitCard", tender(amounts.debitCard))
            add("Ach", tender(amounts.ach))
        }
    }
}
