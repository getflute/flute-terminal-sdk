package com.flute.terminal.sdk.domain.repository

import com.flute.terminal.sdk.model.CalculatedAmounts
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.PosTransactionDetails
import com.flute.terminal.sdk.model.PosTransactionRef
import java.math.BigDecimal

/** POS transaction resource. Pure domain contract — no Android/Retrofit leaks. */
internal interface PosTransactionRepository {
    /** Gateway-computed per-tender amounts (ZCP/surcharge/tip math). */
    suspend fun calculateAmount(
        baseAmount: BigDecimal,
        currencyCode: String,
        pricingType: String?,
        tipAmount: BigDecimal?,
        tipRate: BigDecimal?,
    ): CalculatedAmounts

    /** Creates the record; [amounts] (from calculateAmount) is folded into the deeplink payload. */
    suspend fun create(request: PaymentRequest, terminalId: String, currencyCode: String, amounts: CalculatedAmounts): PosTransactionRef

    /**
     * Creates an unreferenced refund (terminal flow); [referenceId] is the resolved reconciliation
     * handle. The returned ref carries the deeplink payload, same as [create].
     */
    suspend fun createUnreferencedRefund(
        request: com.flute.terminal.sdk.model.RefundRequest,
        terminalId: String,
        currencyCode: String,
        referenceId: String,
    ): PosTransactionRef

    /** Canonical record — the source of truth for the payment outcome. */
    suspend fun get(posTransactionId: String): PosTransactionDetails

    /** ISV-initiated cancel of an in-flight transaction; throws once it's already terminal. */
    suspend fun cancel(posTransactionId: String): PosTransactionDetails
}

/**
 * Post-payment operations on a gateway transaction. All are cloud operations — no terminal
 * interaction and no card data — and all return the resulting transaction record.
 */
internal interface TransactionRepository {
    suspend fun get(transactionId: String): com.flute.terminal.sdk.model.Transaction
    suspend fun capture(transactionId: String, amount: BigDecimal?): com.flute.terminal.sdk.model.Transaction
    suspend fun reverse(transactionId: String, amount: BigDecimal?): com.flute.terminal.sdk.model.Transaction
    suspend fun adjustTip(transactionId: String, tipAmount: BigDecimal?, tipRate: BigDecimal?): com.flute.terminal.sdk.model.Transaction
    suspend fun shareReceipt(transactionId: String, method: String, recipient: String, hasConsent: Boolean)
    suspend fun printReceipt(posTransactionId: String, terminalId: String)
}
