package com.flute.terminal.sdk.callback

import com.flute.terminal.sdk.exception.FluteTerminalException
import com.flute.terminal.sdk.model.PaymentResult

/**
 * Delivered the typed outcome of a payment (terminal-app result or pre-launch failure).
 * `fun interface` so Java and Kotlin callers can both pass a lambda.
 */
fun interface PaymentResultCallback {
    fun onResult(result: PaymentResult)
}

/**
 * Generic async callback for discovery calls. Single-method, so it is a SAM/lambda in Java too.
 * The public API never exposes `suspend` — coroutines stay an implementation detail.
 */
fun interface FluteCallback<T> {
    fun onComplete(result: FluteResult<T>)
}

/** Success/failure envelope used by [FluteCallback]. */
sealed class FluteResult<out T> {
    data class Success<out T>(val value: T) : FluteResult<T>()
    data class Failure(val error: FluteTerminalException) : FluteResult<Nothing>()

    inline fun onSuccess(block: (T) -> Unit): FluteResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (FluteTerminalException) -> Unit): FluteResult<T> {
        if (this is Failure) block(error)
        return this
    }
}
