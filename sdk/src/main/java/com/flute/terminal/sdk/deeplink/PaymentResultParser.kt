package com.flute.terminal.sdk.deeplink

import androidx.activity.result.ActivityResult
import com.flute.terminal.deeplink.FluteDeeplinkContract
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.PaymentResult

/**
 * Parses the raw Activity result returned by the Flute Terminal app into a typed [PaymentResult].
 *
 * NOTE: this reads the Intent extras the terminal app hands back. For money
 * movement the authoritative record is the API (`GET /v2/pos/transactions/{id}` + linked
 * transaction). This returns the Intent-reported result and marks where the canonical re-fetch
 * would slot in.
 */
internal object PaymentResultParser {

    fun parse(result: ActivityResult): PaymentResult {
        val data = result.data
            ?: return PaymentResult.Error(ErrorReason.USER_CANCELLED, "No result returned by Flute Terminal")

        return when (data.getStringExtra(FluteDeeplinkContract.RESULT_STATUS)) {
            FluteDeeplinkContract.STATUS_APPROVED -> PaymentResult.Approved(
                posTransactionId = data.getStringExtra(FluteDeeplinkContract.RESULT_POS_TRANSACTION_ID).orEmpty(),
                transactionId = data.getStringExtra(FluteDeeplinkContract.RESULT_TRANSACTION_ID),
                authCode = data.getStringExtra(FluteDeeplinkContract.RESULT_AUTH_CODE),
                responseCode = data.getStringExtra(FluteDeeplinkContract.RESULT_RESPONSE_CODE),
                receiptData = data.getStringExtra(FluteDeeplinkContract.RESULT_RECEIPT_DATA),
            )

            FluteDeeplinkContract.STATUS_DECLINED -> PaymentResult.Declined(
                posTransactionId = data.getStringExtra(FluteDeeplinkContract.RESULT_POS_TRANSACTION_ID),
                transactionId = data.getStringExtra(FluteDeeplinkContract.RESULT_TRANSACTION_ID),
                responseCode = data.getStringExtra(FluteDeeplinkContract.RESULT_RESPONSE_CODE),
                message = data.getStringExtra(FluteDeeplinkContract.RESULT_MESSAGE),
            )

            FluteDeeplinkContract.STATUS_ERROR -> PaymentResult.Error(
                reason = runCatching {
                    ErrorReason.valueOf(data.getStringExtra(FluteDeeplinkContract.RESULT_ERROR_REASON).orEmpty())
                }.getOrDefault(ErrorReason.UNKNOWN),
                message = data.getStringExtra(FluteDeeplinkContract.RESULT_MESSAGE),
                posTransactionId = data.getStringExtra(FluteDeeplinkContract.RESULT_POS_TRANSACTION_ID),
            )

            else -> PaymentResult.Error(ErrorReason.MALFORMED_RESPONSE, "Unrecognized result payload")
        }
    }
}
