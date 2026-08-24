package com.flute.terminal.sdk.exception

/**
 * Base type for all SDK exceptions. [details] is populated whenever the failure originated from
 * a Flute API response carrying the platform error envelope (error code, correlation id, field
 * errors) — always include `details.correlationId` when reporting issues to Flute support.
 */
sealed class FluteTerminalException(
    message: String,
    cause: Throwable? = null,
    val details: ApiErrorDetails? = null,
) : RuntimeException(message, cause)

/** Used before [com.flute.terminal.sdk.FluteTerminal.initialize]. */
class FluteTerminalNotInitializedException :
    FluteTerminalException("FluteTerminal.initialize(context, config) must be called before use.")

/**
 * [com.flute.terminal.sdk.FluteTerminal.registerForPaymentResult] called too late in the Activity
 * lifecycle (already STARTED). Friendly wrapper around Android's IllegalStateException.
 */
class RegistrationLifecycleException(cause: Throwable) :
    FluteTerminalException(
        "registerForPaymentResult() must be called before the Activity is STARTED (e.g. in onCreate()).",
        cause,
    )

/** Client-side validation failed before any network/deeplink attempt (bad amount, missing field). */
class InvalidPaymentParametersException(message: String) : FluteTerminalException(message)

/** OAuth token acquisition failed. */
class FluteAuthenticationException(
    message: String,
    cause: Throwable? = null,
    details: ApiErrorDetails? = null,
) : FluteTerminalException(message, cause, details)

/** `POST /v2/pos/transactions` failed before the terminal was even invoked. */
class PosTransactionCreationException(
    message: String,
    cause: Throwable? = null,
    details: ApiErrorDetails? = null,
) : FluteTerminalException(message, cause, details)

/** Any other API/transport failure. */
class FluteApiException(
    message: String,
    cause: Throwable? = null,
    details: ApiErrorDetails? = null,
) : FluteTerminalException(message, cause, details)

/** Maps an arbitrary throwable to a typed SDK exception (identity preserved if already one). */
internal fun Throwable.toFluteException(): FluteTerminalException =
    this as? FluteTerminalException ?: FluteApiException(message ?: toString(), this)
