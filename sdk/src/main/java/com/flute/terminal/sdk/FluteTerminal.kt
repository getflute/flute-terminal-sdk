package com.flute.terminal.sdk

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.ref.WeakReference
import com.flute.terminal.sdk.callback.FluteCallback
import com.flute.terminal.sdk.callback.FluteResult
import com.flute.terminal.sdk.callback.PaymentResultCallback
import com.flute.terminal.sdk.deeplink.PaymentResultParser
import com.flute.terminal.sdk.di.FluteSdkGraph
import com.flute.terminal.sdk.exception.FluteTerminalNotInitializedException
import com.flute.terminal.sdk.exception.RegistrationLifecycleException
import com.flute.terminal.sdk.exception.toFluteException
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.PaymentConfig
import com.flute.terminal.sdk.model.PaymentResult
import com.flute.terminal.sdk.model.PendingPaymentCheck
import com.flute.terminal.sdk.model.PosTransactionDetails
import com.flute.terminal.sdk.model.ReceiptDeliveryMethod
import com.flute.terminal.sdk.model.Transaction
import com.flute.terminal.sdk.model.TerminalInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Public entry point for the Flute Terminal SDK (deeplink integration mode).
 *
 * The public surface is designed to be **Java-interoperable**: no `suspend` functions escape
 * (discovery is callback-based), methods are `@JvmStatic`, and results are delivered via SAM
 * callbacks. Kotlin remains the implementation language.
 *
 * Lifecycle:
 * 1. [initialize] once, typically in `Application.onCreate()`.
 * 2. [registerForPaymentResult] in your Activity's `onCreate()` (before STARTED) → [FluteTerminalLauncher].
 * 3. [FluteTerminalLauncher.startPayment] when the cashier starts a sale.
 */
object FluteTerminal {

    @Volatile
    private var graph: FluteSdkGraph? = null

    /**
     * The live graph, for components that outlive it. A registered launcher is created once in an
     * Activity's `onCreate()` but the graph is replaced by an `initialize()` with changed config and
     * dropped by [shutdown], so those components must look it up per use rather than hold it.
     */
    internal fun currentGraph(): FluteSdkGraph? = graph

    /**
     * The most recently registered activity-result launcher. The payment flow runs on the
     * process-wide scope while activities come and go: a launcher captured at registration dies
     * with its activity, and launching on it throws "unregistered ActivityResultLauncher" (observed
     * when the ISV activity was recreated during the create call). Launch-time code prefers this —
     * the live activity's launcher — over the one captured at registration. Weak so the SDK never
     * pins a destroyed activity's registry in memory.
     */
    @Volatile
    private var latestLauncher: WeakReference<ActivityResultLauncher<Intent>>? = null

    internal fun currentLauncher(): ActivityResultLauncher<Intent>? = latestLauncher?.get()

    /**
     * One-time setup, typically in `Application.onCreate()`.
     *
     * If [config] carries `clientId`/`clientSecret`, they are persisted (encrypted) and reused on
     * every later launch — so you may pass them once and omit them thereafter. Initialization then
     * warms up asynchronously: fetch + cache the OAuth token, fetch + persist the payment config,
     * and start the background token-refresh loop. Warm-up is best-effort; pass [onReady] to observe
     * success/failure (e.g. to surface a bad-credentials error early). On-demand calls still work if
     * warm-up is skipped or fails.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        config: FluteTerminalConfig,
        onReady: FluteCallback<Unit>? = null,
    ) {
        // Idempotent: apps commonly call initialize() from an Activity's onCreate, which re-runs on
        // every recreation. Same config → reuse the live graph (its refresh loop is already armed);
        // building a new graph here would leak the old one's refresh loop — N leaked loops all
        // waking at the token's expiry produced an N-wide token-request stampede.
        graph?.let { existing ->
            if (existing.config == config) {
                existing.warmUp { result -> onReady?.onComplete(result.toFluteResult()) }
                return
            }
            existing.shutdown() // config changed: stop the old graph's refresh loop before replacing
        }

        val g = FluteSdkGraph(context.applicationContext, config)
        // Config credentials are a SEED, not an override: apps that pass them on every launch must
        // not clobber credentials provisioned at runtime (which are also environment-specific), or
        // provisioning would silently stop sticking. Rotate via provisionCredentials().
        if (config.clientId != null && config.clientSecret != null &&
            !g.credentials.wasProvisionedAtRuntime()
        ) {
            g.credentials.save(config.clientId, config.clientSecret)
        }
        graph = g
        g.warmUp { result -> onReady?.onComplete(result.toFluteResult()) }
    }

    private fun Result<Unit>.toFluteResult(): FluteResult<Unit> = fold(
        onSuccess = { FluteResult.Success(Unit) },
        onFailure = { FluteResult.Failure(it.toFluteException()) },
    )

    /**
     * Provision (or replace) the merchant API credentials at runtime — for flows where they arrive
     * after startup (device provisioning, the ISV's own backend, a QA build). Persisted encrypted;
     * any token from previous credentials is dropped, and the SDK re-authenticates and re-warms.
     * [onReady] reports whether the new credentials work — surface failures to the operator.
     */
    @JvmStatic
    @JvmOverloads
    fun provisionCredentials(clientId: String, clientSecret: String, onReady: FluteCallback<Unit>? = null) {
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) { "clientId and clientSecret must not be blank" }
        requireGraph().provisionCredentials(clientId, clientSecret) { result ->
            onReady?.onComplete(
                result.fold(
                    onSuccess = { FluteResult.Success(Unit) },
                    onFailure = { FluteResult.Failure(it.toFluteException()) },
                ),
            )
        }
    }

    /** True if merchant API credentials are already provisioned on this device. */
    @JvmStatic
    fun hasCredentials(): Boolean = graph?.credentials?.hasCredentials() ?: false

    /** Stops the background token-refresh loop. Optional; call on full app teardown. */
    @JvmStatic
    fun shutdown() {
        graph?.shutdown()
        graph = null
    }

    /**
     * Registers the underlying `ActivityResultLauncher`. MUST be called before the Activity reaches
     * STARTED (i.e. in `onCreate()`). [callback] receives the typed [PaymentResult] for both the
     * terminal-app outcome and any pre-launch failure (auth/create/app-not-installed).
     */
    @JvmStatic
    fun registerForPaymentResult(
        activity: ComponentActivity,
        callback: PaymentResultCallback,
    ): FluteTerminalLauncher {
        requireGraph() // fail fast if initialize() was never called
        val coordinator = PaymentFlowCoordinator(::currentGraph, callback)
        val activityLauncher = try {
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                coordinator.onActivityResult(result)
            }
        } catch (e: IllegalStateException) {
            throw RegistrationLifecycleException(e)
        }
        latestLauncher = WeakReference(activityLauncher)
        return FluteTerminalLauncher(activity, ::currentGraph, activityLauncher, coordinator)
    }

    /**
     * Process-death recovery: if the app was killed mid-payment, the SDK still holds the in-flight
     * transaction id — this reconciles it against the API. Call once at startup, before taking new
     * payments. If the pending payment is still in progress on the terminal, the session is kept
     * (check again); once a final outcome is returned, the session is cleared.
     */
    @JvmStatic
    fun checkPendingPayment(callback: FluteCallback<PendingPaymentCheck>) {
        val g = requireGraph()
        g.sdkScope.launch {
            try {
                val pending = g.sessionStore.current()
                if (pending == null) {
                    callback.onComplete(FluteResult.Success(PendingPaymentCheck.none()))
                    return@launch
                }
                // Sessions must always reach closure — two dead-ends are closed out here rather
                // than being re-reported on every app start forever:
                if (g.sessionStore.isStale(pending)) {
                    g.sessionStore.clear()
                    callback.onComplete(
                        FluteResult.Success(
                            PendingPaymentCheck(
                                hasPending = true,
                                stillInProgress = false,
                                result = PaymentResult.Error(
                                    ErrorReason.TIMEOUT,
                                    "Pending payment expired unresolved; check the transaction in the Flute dashboard.",
                                    pending.posTransactionId,
                                ),
                            ),
                        ),
                    )
                    return@launch
                }
                val outcome = try {
                    g.resolvePaymentOutcome(pending.posTransactionId)
                } catch (t: Throwable) {
                    val ex = t.toFluteException()
                    if (ex.details?.httpStatus == 404) {
                        // The record no longer exists — the session can never resolve; drop it.
                        g.sessionStore.clear()
                        PaymentResult.Error(
                            ErrorReason.UNKNOWN,
                            "Pending payment record no longer exists.",
                            pending.posTransactionId,
                            ex.details?.correlationId,
                        )
                    } else {
                        throw t // transient (network/5xx): keep the session, report the failure
                    }
                }
                if (outcome != null) g.sessionStore.clear()
                callback.onComplete(
                    FluteResult.Success(
                        PendingPaymentCheck(
                            hasPending = true,
                            stillInProgress = outcome == null,
                            result = outcome,
                        ),
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                callback.onComplete(FluteResult.Failure(t.toFluteException()))
            }
        }
    }

    /**
     * The serial identifying this terminal: the configured override if one was supplied, otherwise
     * the serial auto-read from the device. Null on platforms that hide it (emulator, Android 10+
     * non-privileged apps) when no override is configured. Useful for diagnostics/QA display.
     */
    @JvmStatic
    fun deviceSerialNumber(): String? = graph?.serialNumber

    /**
     * The POS transaction id of the payment currently in flight, or null when none is. This is how
     * an ISV obtains the id to [cancelPayment] a payment that hasn't produced its result yet — the
     * result callback only fires at the end.
     */
    @JvmStatic
    fun pendingPosTransactionId(): String? = graph?.sessionStore?.current()?.posTransactionId

    /**
     * ISV-initiated cancel of an in-flight POS transaction
     * (`POST /v2/pos/transactions/{id}/cancel`).
     *
     * On success the terminal is notified and abandons the payment; the registered payment
     * callback still delivers the final outcome for that payment (typically USER_CANCELLED after
     * the canonical re-fetch) — this callback only reports whether the cancel request itself was
     * accepted. The backend rejects the cancel (typed failure here) once the transaction already
     * reached a terminal state, e.g. it was approved or declined in the meantime — treat that as
     * "too late to cancel" and rely on the payment callback's outcome.
     */
    @JvmStatic
    fun cancelPayment(posTransactionId: String, callback: FluteCallback<PosTransactionDetails>) {
        val g = requireGraph()
        g.sdkScope.launch {
            try {
                val details = g.cancelPosTransaction(posTransactionId)
                g.log(FluteLogLevel.INFO, "Payment cancel accepted (posTransactionId=$posTransactionId)")
                callback.onComplete(FluteResult.Success(details))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                g.log(FluteLogLevel.WARN, "Payment cancel rejected (posTransactionId=$posTransactionId)", t)
                callback.onComplete(FluteResult.Failure(t.toFluteException()))
            }
        }
    }


    // ------------------------------------------------------------------ post-payment operations
    // All are cloud operations: no terminal interaction, no card data, and each returns the
    // resulting transaction record so the ISV never has to follow up with a lookup.

    /**
     * Completes an authorization taken with [com.flute.terminal.sdk.model.CaptureMethod.MANUAL]
     * (`POST /v2/transactions/{id}/capture`). Omit [amount] to capture the full authorization, or
     * pass a smaller one for a partial capture.
     */
    @JvmStatic
    @JvmOverloads
    fun capture(
        transactionId: String,
        amount: java.math.BigDecimal? = null,
        callback: FluteCallback<Transaction>,
    ) = operation(callback, "capture", transactionId) { it.transactions.capture(transactionId, amount) }

    /**
     * Reverses a known transaction (`POST /v2/transactions/{id}/reversal`) — the backend voids it
     * while that is still possible, otherwise refunds it. Omit [amount] for the full value, or pass
     * a smaller one for a partial refund (see [Transaction.availableRefundAmount]).
     *
     * This is the *referenced* path and needs no terminal or card. To return money with no
     * originating transaction, use [FluteTerminalLauncher.startRefund] instead.
     */
    @JvmStatic
    @JvmOverloads
    fun reverseTransaction(
        transactionId: String,
        amount: java.math.BigDecimal? = null,
        callback: FluteCallback<Transaction>,
    ) = operation(callback, "reversal", transactionId) { it.transactions.reverse(transactionId, amount) }

    /**
     * Adjusts the tip on a completed transaction (`POST /v2/transactions/{id}/tip-adjustment`) — for
     * merchants who add gratuity after the sale. Supply an amount OR a rate (raw percent), not both.
     */
    @JvmStatic
    @JvmOverloads
    fun adjustTip(
        transactionId: String,
        tipAmount: java.math.BigDecimal? = null,
        tipRatePercent: java.math.BigDecimal? = null,
        callback: FluteCallback<Transaction>,
    ): Unit {
        require(tipAmount == null || tipRatePercent == null) {
            "Supply either tipAmount or tipRatePercent, not both"
        }
        operation(callback, "tip-adjustment", transactionId) {
            it.transactions.adjustTip(transactionId, tipAmount, tipRatePercent)
        }
    }

    /** Looks up a transaction (`GET /v2/transactions/{id}`). */
    @JvmStatic
    fun getTransaction(transactionId: String, callback: FluteCallback<Transaction>) =
        operation(callback, "lookup", transactionId) { it.transactions.get(transactionId) }

    /**
     * Sends the customer their receipt (`POST /v2/transactions/{id}/share-receipt`).
     *
     * SMS is the only channel the platform accepts, so [recipient] is a mobile number in E.164
     * form — the platform validates it as one. [hasCustomerConsent] must reflect real consent to be
     * contacted; it is passed through and recorded, and the request is rejected without it.
     */
    @JvmStatic
    fun shareReceipt(
        transactionId: String,
        method: ReceiptDeliveryMethod,
        recipient: String,
        hasCustomerConsent: Boolean,
        callback: FluteCallback<Unit>,
    ) = operation(callback, "share-receipt", transactionId) {
        it.transactions.shareReceipt(
            transactionId,
            method = when (method) {
                ReceiptDeliveryMethod.SMS -> "Sms"
            },
            recipient = recipient,
            hasConsent = hasCustomerConsent,
        )
    }

    /**
     * Reprints a receipt on this device's terminal
     * (`POST /v2/pos/transactions/{id}/print-receipt`). Takes the **POS transaction** id; the
     * terminal is resolved from the device serial.
     */
    @JvmStatic
    fun printReceipt(posTransactionId: String, callback: FluteCallback<Unit>) =
        operation(callback, "print-receipt", posTransactionId) {
            it.transactions.printReceipt(posTransactionId, it.requireTerminalId())
        }

    /**
     * Shared plumbing for the operations above: runs on the SDK scope (so the callback lands on the
     * main thread), logs a redacted line, and converts any failure into a typed [FluteResult.Failure]
     * instead of throwing at the caller.
     */
    private fun <T> operation(
        callback: FluteCallback<T>,
        name: String,
        id: String,
        block: suspend (FluteSdkGraph) -> T,
    ) {
        val g = requireGraph()
        g.sdkScope.launch {
            try {
                val result = block(g)
                g.log(FluteLogLevel.INFO, "$name ok (id=$id)")
                callback.onComplete(FluteResult.Success(result))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                g.log(FluteLogLevel.WARN, "$name failed (id=$id)", t)
                callback.onComplete(FluteResult.Failure(t.toFluteException()))
            }
        }
    }

    /** Parses a raw Activity result into a typed [PaymentResult] (legacy `onActivityResult()` support). */
    @JvmStatic
    fun parseResult(result: ActivityResult): PaymentResult = PaymentResultParser.parse(result)

    /** Terminals visible to the merchant (`GET /v2/terminals`). Async; result on the main thread. */
    @JvmStatic
    fun fetchTerminals(callback: FluteCallback<List<TerminalInfo>>) {
        val g = requireGraph()
        g.sdkScope.launch {
            try {
                callback.onComplete(FluteResult.Success(g.getTerminals()))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                callback.onComplete(FluteResult.Failure(t.toFluteException()))
            }
        }
    }

    /** Merchant payment config (`GET /v2/settings/payment-config`). Async; result on the main thread. */
    @JvmStatic
    fun fetchPaymentConfig(callback: FluteCallback<PaymentConfig>) {
        val g = requireGraph()
        g.sdkScope.launch {
            try {
                callback.onComplete(FluteResult.Success(g.getPaymentConfig()))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                callback.onComplete(FluteResult.Failure(t.toFluteException()))
            }
        }
    }

    internal fun requireGraph(): FluteSdkGraph =
        graph ?: throw FluteTerminalNotInitializedException()
}