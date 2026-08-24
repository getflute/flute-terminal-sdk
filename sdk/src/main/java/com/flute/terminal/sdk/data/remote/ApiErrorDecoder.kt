package com.flute.terminal.sdk.data.remote

import com.flute.terminal.sdk.exception.ApiErrorDetails
import com.flute.terminal.sdk.exception.FluteApiException
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.HttpException

/**
 * Decodes the platform-wide error envelope (PascalCase keys — validated live against UAT) out of
 * failed HTTP responses, so every typed SDK exception carries the real reason, error code, and
 * correlation id instead of a generic "request failed".
 */
internal object ApiErrorDecoder {

    private val gson = Gson()

    /** Wire shape of the envelope. Kept internal; the public projection is [ApiErrorDetails]. */
    private data class ErrorEnvelope(
        @SerializedName("Errors") val errors: Map<String, List<String>>?,
        @SerializedName("StatusCode") val statusCode: Int?,
        @SerializedName("Source") val source: String?,
        @SerializedName("CorrelationId") val correlationId: String?,
        @SerializedName("ErrorCode") val errorCode: String?,
        @SerializedName("Title") val title: String?,
        @SerializedName("Cause") val cause: String?,
        @SerializedName("Resolution") val resolution: String?,
    )

    /**
     * OAuth2 error body (`/oauth2/token`) — a different shape from the platform envelope. Decoding
     * it turns "API request failed (HTTP 401)" into the actual reason, e.g. an invalid client_id
     * because the credentials belong to a different environment.
     */
    private data class OAuthError(
        @SerializedName("error") val error: String?,
        @SerializedName("error_description") val description: String?,
    )

    fun decode(e: HttpException): ApiErrorDetails {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val envelope = body?.let { runCatching { gson.fromJson(it, ErrorEnvelope::class.java) }.getOrNull() }
        val oauth = body
            ?.let { runCatching { gson.fromJson(it, OAuthError::class.java) }.getOrNull() }
            ?.takeIf { it.error != null }

        return ApiErrorDetails(
            httpStatus = e.code(),
            errorCode = envelope?.errorCode ?: oauth?.error,
            correlationId = envelope?.correlationId,
            title = envelope?.title ?: oauth?.description ?: oauth?.error,
            cause = envelope?.cause,
            resolution = envelope?.resolution,
            source = envelope?.source,
            fieldErrors = envelope?.errors ?: emptyMap(),
        )
    }
}

/**
 * Runs an API call, translating HTTP failures into [FluteApiException] carrying decoded
 * [ApiErrorDetails]. Repositories wrap calls with this so no raw [HttpException] escapes the
 * data layer. Non-HTTP failures (IO, serialization) pass through for the caller's own wrapping.
 */
internal suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    val details = ApiErrorDecoder.decode(e)
    throw FluteApiException(details.summary(), e, details)
}
