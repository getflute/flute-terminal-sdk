package com.flute.terminal.sdk.data.remote

import com.flute.terminal.sdk.data.remote.dto.CalculateAmountRequest
import com.flute.terminal.sdk.data.remote.dto.CalculateAmountResponse
import com.flute.terminal.sdk.data.remote.dto.CaptureRequest
import com.flute.terminal.sdk.data.remote.dto.CreatePosReversalRequest
import com.flute.terminal.sdk.data.remote.dto.LinkedTransactionDto
import com.flute.terminal.sdk.data.remote.dto.PrintReceiptRequest
import com.flute.terminal.sdk.data.remote.dto.ReversalRequest
import com.flute.terminal.sdk.data.remote.dto.ShareReceiptRequest
import com.flute.terminal.sdk.data.remote.dto.TipAdjustmentRequest
import com.flute.terminal.sdk.data.remote.dto.CreatePosTransactionRequest
import com.flute.terminal.sdk.data.remote.dto.PaymentConfigResponse
import com.flute.terminal.sdk.data.remote.dto.PosTransactionResponse
import com.flute.terminal.sdk.data.remote.dto.TerminalListResponse
import com.flute.terminal.sdk.data.remote.dto.TokenResponse
import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Identity OAuth surface. Base URL = [com.flute.terminal.sdk.FluteTerminalConfig.identityBaseUrl]. */
internal interface FluteIdentityApi {
    @FormUrlEncoded
    @POST("oauth2/token")
    suspend fun token(
        @Field("grant_type") grantType: String = "client_credentials",
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
    ): TokenResponse
}

/** IsvApiBff v2 surface. Base URL = [com.flute.terminal.sdk.FluteTerminalConfig.apiBaseUrl]. */
internal interface FluteApi {
    // Returns the raw JSON object so the SDK can both read typed fields AND relay the exact
    // create-response body to the terminal as the deeplink notification payload (verbatim, no
    // reconstruction). See FluteDeeplinkContract.EXTRA_NOTIFICATION_PAYLOAD.
    @POST("v2/pos/transactions")
    suspend fun createPosTransaction(
        @Header("Authorization") bearer: String,
        @Body body: CreatePosTransactionRequest,
    ): JsonObject

    /**
     * Unreferenced refund on the terminal. Same raw-JSON return as create: the body is relayed to
     * the terminal as the deeplink notification payload.
     */
    @POST("v2/pos/transactions/reversal")
    suspend fun createPosReversal(
        @Header("Authorization") bearer: String,
        @Body body: CreatePosReversalRequest,
    ): JsonObject

    /** Canonical result for post-resume reconciliation. */
    @GET("v2/pos/transactions/{id}")
    suspend fun getPosTransaction(
        @Header("Authorization") bearer: String,
        @Path("id") posTransactionId: String,
    ): PosTransactionResponse

    /**
     * ISV-initiated cancel of an in-flight POS transaction. The backend rejects it (400) once the
     * transaction has reached a terminal state (e.g. DeclinedByProcessor) — that surfaces as a
     * typed FluteApiException with the reason.
     */
    @POST("v2/pos/transactions/{id}/cancel")
    suspend fun cancelPosTransaction(
        @Header("Authorization") bearer: String,
        @Path("id") posTransactionId: String,
    ): PosTransactionResponse

    /** Post-payment operations. All return the transaction record (same shape as linkedTransaction). */
    @GET("v2/transactions/{id}")
    suspend fun getTransaction(
        @Header("Authorization") bearer: String,
        @Path("id") transactionId: String,
    ): LinkedTransactionDto

    @POST("v2/transactions/{id}/capture")
    suspend fun captureTransaction(
        @Header("Authorization") bearer: String,
        @Path("id") transactionId: String,
        @Body body: CaptureRequest,
    ): LinkedTransactionDto

    @POST("v2/transactions/{id}/reversal")
    suspend fun reverseTransaction(
        @Header("Authorization") bearer: String,
        @Path("id") transactionId: String,
        @Body body: ReversalRequest,
    ): LinkedTransactionDto

    @POST("v2/transactions/{id}/tip-adjustment")
    suspend fun adjustTip(
        @Header("Authorization") bearer: String,
        @Path("id") transactionId: String,
        @Body body: TipAdjustmentRequest,
    ): LinkedTransactionDto

    @POST("v2/transactions/{id}/share-receipt")
    suspend fun shareReceipt(
        @Header("Authorization") bearer: String,
        @Path("id") transactionId: String,
        @Body body: ShareReceiptRequest,
    ): retrofit2.Response<Unit>

    @POST("v2/pos/transactions/{id}/print-receipt")
    suspend fun printReceipt(
        @Header("Authorization") bearer: String,
        @Path("id") posTransactionId: String,
        @Body body: PrintReceiptRequest,
    ): retrofit2.Response<Unit>

    @GET("v2/terminals")
    suspend fun listTerminals(
        @Header("Authorization") bearer: String,
        @Query("serialNumber") serialNumber: String? = null,
    ): TerminalListResponse

    @GET("v2/settings/payment-config")
    suspend fun getPaymentConfig(
        @Header("Authorization") bearer: String,
    ): PaymentConfigResponse

    /**
     * Gateway-computed per-tender amounts (ZCP/surcharge/tip math) — source of the payload's
     * Amounts. POST with a JSON body: the endpoint moved from GET+query (a GET here now
     * mis-routes to `GET /v2/transactions/{transactionId}` and 400s).
     */
    @POST("v2/transactions/calculate-amount")
    suspend fun calculateAmount(
        @Header("Authorization") bearer: String,
        @Body request: CalculateAmountRequest,
    ): CalculateAmountResponse
}
