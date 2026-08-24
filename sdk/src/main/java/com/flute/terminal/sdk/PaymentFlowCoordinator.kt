package com.flute.terminal.sdk

import androidx.activity.result.ActivityResult
import com.flute.terminal.sdk.callback.PaymentResultCallback
import com.flute.terminal.sdk.deeplink.PaymentResultParser
import com.flute.terminal.sdk.di.FluteSdkGraph
import com.flute.terminal.sdk.exception.toFluteException
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.PaymentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates one payment attempt end to end:
 *
 * ```
 * CREATED ──launch──▶ AWAITING_TERMINAL ──callback──▶ RESOLVING ──▶ delivered (once)
 *                          │ timeout ────────────────────▲
 * ```
 *
 * Source-of-truth rule: the Intent returned by the terminal app
 * is a **hint**; the canonical outcome is re-fetched from `GET /v2/pos/transactions/{id}`. If the
 * API is unreachable at resolution time, the hint is delivered best-effort rather than hanging.
 *
 * The in-flight id is persisted ([FluteSdkGraph.sessionStore]) before launch, so a killed ISV app
 * can reconcile via [FluteTerminal.checkPendingPayment] on next start.
 */
internal class PaymentFlowCoordinator(
    private val graphProvider: () -> FluteSdkGraph?,
    private val callback: PaymentResultCallback,
) {
    /**
     * The live graph, looked up per use rather than captured: `initialize()` with changed config
     * replaces the graph and cancels the old one, and a coordinator registered in `onCreate()`
     * outlives that. Null once `shutdown()` has run.
     */
    private val graphOrNull: FluteSdkGraph? get() = graphProvider()

    /** For the payment flow itself, which only runs while a graph exists. */
    private val graph: FluteSdkGraph
        get() = graphOrNull ?: throw IllegalStateException("FluteTerminal was shut down mid-payment.")

    /**
     * Callback delivery is scoped to this coordinator, never to the graph: the exactly-once
     * guarantee must hold even when the graph is torn down mid-payment. Main-dispatched, so ISV
     * callbacks can touch UI directly.
     */
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val delivered = AtomicBoolean(false)
    private var timeoutJob: Job? = null

    /**
     * True from the moment startPayment is accepted until its result is delivered. Guards the
     * whole attempt (including the create window before a posTransactionId exists), so a double
     * tap can't run two payments or clobber the first one's persisted session.
     */
    private val attemptActive = AtomicBoolean(false)

    @Volatile
    private var activePosTransactionId: String? = null

    /** Claims the single payment slot. False → another payment is still running. */
    fun tryBeginAttempt(): Boolean = attemptActive.compareAndSet(false, true)

    /** Releases the slot without delivering (cancellation mid-create — there is nothing to report). */
    fun releaseAttempt() {
        attemptActive.set(false)
    }

    /**
     * Rejects a concurrent startPayment. Deliberately bypasses [deliverOnce]: the once-only guard
     * and the persisted session belong to the payment that IS running and must stay untouched.
     */
    fun rejectConcurrentAttempt() {
        callback.onResult(
            PaymentResult.Error(
                ErrorReason.ALREADY_IN_PROGRESS,
                "A payment is already in progress on this terminal; wait for its result before starting another.",
                activePosTransactionId,
            ),
        )
    }

    /** New payment attempt: reset the once-only guard and persist the in-flight id. */
    fun beginSession(posTransactionId: String) {
        delivered.set(false)
        timeoutJob?.cancel()
        activePosTransactionId = posTransactionId
        graph.sessionStore.begin(posTransactionId)
        graph.log(FluteLogLevel.INFO, "Payment started (posTransactionId=$posTransactionId)")
    }

    /**
     * Arms the no-callback watchdog (terminal app killed/crashed → resolve via the API). Runs on
     * the graph's process-wide scope, NOT the activity's: the ISV activity is routinely destroyed
     * while the terminal app is in front, and a watchdog tied to its lifecycle would die with it —
     * leaving a terminal-app hang with no exit path until the next app start.
     */
    fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = graph.sdkScope.launch {
            delay(graph.config.terminalResultTimeoutSeconds * 1_000)
            // Stale-watchdog guard. This watchdog lives on the process scope so a genuinely killed
            // terminal still resolves — but that also means an ORPHANED coordinator (one whose ISV
            // Activity was destroyed and replaced mid-flow) keeps its watchdog alive. If this
            // payment was already delivered (by this or another coordinator instance, which clears
            // the persisted session), firing now would issue a redundant GET and re-deliver an
            // already-final outcome minutes later. Only proceed if this attempt is still the
            // pending one.
            if (delivered.get()) return@launch
            if (graph.sessionStore.current()?.posTransactionId != activePosTransactionId) return@launch
            resolveAndDeliver(hint = null, timedOut = true)
        }
    }

    /**
     * Terminal app returned — parse the hint, then resolve canonically. Resolution runs on the
     * graph's scope so an activity teardown mid-resolve can't consume the result without a
     * callback; the scope is main-dispatched, so the callback still arrives on the main thread.
     */
    fun onActivityResult(result: ActivityResult) {
        val hint = PaymentResultParser.parse(result)
        val g = graphOrNull ?: run {
            // Shut down while the terminal app was in front: there is no API to reconcile against,
            // so deliver the terminal's hint rather than leave the caller with no result at all.
            deliverOnce(hint ?: notInitialized("The SDK was shut down before the payment resolved."))
            return
        }
        g.sdkScope.launch { resolveAndDeliver(hint = hint, timedOut = false) }
    }

    /**
     * startPayment reached a coordinator whose SDK is gone (`shutdown()`, or never initialized).
     * Frees the slot and reports it, instead of dispatching onto a cancelled scope — which used to
     * drop the payment silently and leave the slot claimed for the life of the process.
     */
    fun deliverNotInitialized() {
        deliverOnce(notInitialized("FluteTerminal is not initialized; call initialize() first."))
    }

    private fun notInitialized(message: String) =
        PaymentResult.Error(ErrorReason.NOT_INITIALIZED, message, activePosTransactionId)

    /** Failure before the terminal app was ever launched (auth/create/app-not-installed/launch). */
    fun deliverPreLaunchFailure(error: PaymentResult.Error) {
        // A transaction that was created but never picked up by a terminal would sit InProgress on
        // the backend, blocking every later create on this terminal ("Transaction is already in
        // progress") — and the terminal's own stuck-transaction sweep can't see it because the
        // terminal app never came to the foreground. Best-effort cancel it server-side; if this
        // cancel also fails, the ISV still holds the id (in the delivered error) to cancel manually.
        val g = graphOrNull
        val orphanId = error.posTransactionId
        if (g != null && orphanId != null) {
            g.sdkScope.launch {
                try {
                    g.cancelPosTransaction(orphanId)
                    g.log(
                        FluteLogLevel.INFO,
                        "Cancelled orphaned POS transaction after pre-launch failure (posTransactionId=$orphanId)",
                    )
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    g.log(
                        FluteLogLevel.WARN,
                        "Could not cancel orphaned POS transaction (posTransactionId=$orphanId): ${t.message}",
                    )
                }
            }
        }
        // Drop the persisted session either way — no terminal will ever report this attempt.
        g?.sessionStore?.clear()
        deliverOnce(error)
    }

    private suspend fun resolveAndDeliver(hint: PaymentResult?, timedOut: Boolean) {
        if (delivered.get()) return
        val posTransactionId = activePosTransactionId ?: graph.sessionStore.current()?.posTransactionId

        if (posTransactionId == null) {
            deliverFinal(hint ?: PaymentResult.Error(ErrorReason.UNKNOWN, "No active payment to resolve."))
            return
        }

        val authCodeHint = (hint as? PaymentResult.Approved)?.authCode
        val canonical: PaymentResult? = try {
            resolveWithRetry(posTransactionId, authCodeHint)
        } catch (t: Throwable) {
            val ex = t.toFluteException()
            // API unreachable at resolution time: fall back to the hint rather than hang the sale.
            hint ?: PaymentResult.Error(
                ErrorReason.UNKNOWN,
                ex.message ?: "Could not resolve payment outcome.",
                posTransactionId,
                ex.details?.correlationId,
            )
        }

        when {
            canonical != null -> deliverFinal(canonical)
            timedOut -> {
                // Still in progress with no callback: report timeout but KEEP the session so
                // checkPendingPayment() can reconcile if the terminal finishes later.
                deliverOnce(
                    PaymentResult.Error(
                        ErrorReason.TIMEOUT,
                        "No result from the terminal within ${graph.config.terminalResultTimeoutSeconds}s.",
                        posTransactionId,
                    ),
                )
            }
            else -> {
                // The terminal came back but the record is still InProgress — the flow ended
                // without the backend being told (device-level EMV failure, terminal killed).
                // Nothing will ever finish it: the terminal's stuck-transaction sweep runs on its
                // home screen, which a deeplink flow returns from rather than to. Left alone it
                // blocks this terminal's next transaction with "Transaction is already in
                // progress", so release it here, where the ISV app is foreground and online.
                releaseUnfinishedTransaction(posTransactionId)
                deliverFinal(
                    hint ?: PaymentResult.Error(
                        ErrorReason.MALFORMED_RESPONSE,
                        "Terminal returned but the transaction is still in progress.",
                        posTransactionId,
                    ),
                )
            }
        }
    }

    /**
     * Best-effort cancel of a POS transaction the terminal abandoned. A backend rejection (400 once
     * it did reach a terminal state) is the expected outcome of a race and is not worth surfacing —
     * the outcome delivered to the ISV is unaffected either way.
     */
    private suspend fun releaseUnfinishedTransaction(posTransactionId: String) {
        val g = graphOrNull ?: return
        try {
            g.cancelPosTransaction(posTransactionId)
            g.log(FluteLogLevel.INFO, "Released unfinished POS transaction (posTransactionId=$posTransactionId)")
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            g.log(
                FluteLogLevel.WARN,
                "Could not release unfinished POS transaction (posTransactionId=$posTransactionId): ${t.message}",
            )
        }
    }

    /** The API can lag the terminal by a moment — retry briefly before falling back. */
    private suspend fun resolveWithRetry(posTransactionId: String, authCodeHint: String?): PaymentResult? {
        repeat(RESOLVE_ATTEMPTS - 1) {
            graph.resolvePaymentOutcome(posTransactionId, authCodeHint)?.let { return it }
            delay(RESOLVE_RETRY_DELAY_MS)
        }
        return graph.resolvePaymentOutcome(posTransactionId, authCodeHint)
    }

    /** Final outcome reached: clear the persisted session, then deliver. */
    private fun deliverFinal(result: PaymentResult) {
        graphOrNull?.sessionStore?.clear()
        deliverOnce(result)
    }

    private fun deliverOnce(result: PaymentResult) {
        if (delivered.compareAndSet(false, true)) {
            timeoutJob?.cancel()
            attemptActive.set(false) // the slot frees up the moment the outcome is delivered
            logOutcome(result)
            // Guarantee the ISV callback lands on the main thread regardless of the caller's
            // context, so consumers can touch UI directly. Main.immediate keeps it synchronous
            // when we are already on main (the common path).
            callbackScope.launch { callback.onResult(result) }
        }
    }

    /** Redacted one-liner for the ISV logger — ids and codes only, never card/secret data. */
    private fun logOutcome(result: PaymentResult) = when (result) {
        is PaymentResult.Approved -> graphOrNull?.log(
            FluteLogLevel.INFO,
            "Payment APPROVED (posTransactionId=${result.posTransactionId}, responseCode=${result.responseCode})",
        )
        is PaymentResult.Declined -> graphOrNull?.log(
            FluteLogLevel.INFO,
            "Payment DECLINED (posTransactionId=${result.posTransactionId}, responseCode=${result.responseCode})",
        )
        is PaymentResult.Error -> graphOrNull?.log(
            if (result.reason == ErrorReason.USER_CANCELLED) FluteLogLevel.INFO else FluteLogLevel.WARN,
            "Payment ${result.reason} (posTransactionId=${result.posTransactionId}, correlationId=${result.correlationId})",
        )
    }

    private companion object {
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_RETRY_DELAY_MS = 2_000L
    }
}
