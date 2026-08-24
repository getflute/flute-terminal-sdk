package com.flute.terminal.sdk.exception

/**
 * Structured error details returned by the Flute platform on any failed API call.
 *
 * Mirrors the platform-wide error envelope (validated live against UAT):
 * `Errors` (field → messages), `StatusCode`, `Source`, `CorrelationId`, `ErrorCode`, `Title`,
 * `Cause`, `Resolution`. Surface [correlationId] in support tickets — it is the trace id that
 * links this failure across Flute's backend services.
 */
data class ApiErrorDetails(
    val httpStatus: Int,
    val errorCode: String?,
    val correlationId: String?,
    val title: String?,
    val cause: String?,
    val resolution: String?,
    val source: String?,
    /** Per-field validation messages, e.g. `ConnectionStatus -> ["Terminal … was Offline."]`. */
    val fieldErrors: Map<String, List<String>>,
) {
    /** Human-readable one-liner: first field error if present, else title/cause. */
    fun summary(): String {
        val firstFieldError = fieldErrors.values.firstOrNull()?.firstOrNull()
        return firstFieldError ?: title ?: cause ?: "API request failed (HTTP $httpStatus)"
    }
}
