package com.flute.terminal.sdk

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.flute.terminal.sdk.deeplink.DeeplinkIntents
import com.flute.terminal.sdk.di.FluteSdkGraph
import com.flute.terminal.sdk.exception.InvalidPaymentParametersException
import com.flute.terminal.sdk.exception.toFluteException
import com.flute.terminal.sdk.model.CalculatedAmounts
import com.flute.terminal.sdk.model.ErrorReason
import com.flute.terminal.sdk.model.PaymentRequest
import com.flute.terminal.sdk.model.RefundRequest
import com.flute.terminal.sdk.model.PaymentResult
import com.flute.terminal.sdk.model.ZeroCostOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returned by [FluteTerminal.registerForPaymentResult]. Starts payments; all outcomes — terminal
 * result, timeout, or pre-launch failure — arrive on the registered callback exactly once, with the
 * canonical outcome re-fetched from the API (the terminal Intent is only a hint).
 *
 * The ISV supplies only the [PaymentRequest]; the SDK autofills `terminalId` (from the device
 * serial at init) and `currencyCode` (from merchant config), and calls `calculate-amount` for the
 * gateway-computed amounts before creating the transaction.
 */
class FluteTerminalLauncher internal constructor(
    private val activity: ComponentActivity,
    private val graphProvider: () -> FluteSdkGraph?,
    private val activityLauncher: ActivityResultLauncher<Intent>,
    private val coordinator: PaymentFlowCoordinator,
) {
    private val appContext: Context = activity.applicationContext

    /**
     * Resolved per call, not captured at registration: `initialize()` with changed config (an
     * environment switch, new credentials) replaces the graph and cancels the old one, while this
     * launcher — registered once in `onCreate()` — lives on. Dispatching onto the cancelled scope
     * ran nothing at all, so the payment vanished and the single attempt slot was never released.
     */
    private val graph: FluteSdkGraph
        get() = graphProvider() ?: throw IllegalStateException("FluteTerminal was shut down mid-payment.")

    /**
     * Launches on the most recently registered launcher, falling back to the one captured at
     * registration. The flow runs on the process-wide scope while activities come and go: if the
     * ISV activity is recreated during the create call, this instance's launcher is already
     * unregistered and launching on it throws — but the recreated activity has registered a fresh
     * one, and the persisted session lets its coordinator resolve the outcome.
     */
    private fun launchTerminal(intent: Intent) {
        val freshest = FluteTerminal.currentLauncher() ?: activityLauncher
        try {
            freshest.launch(intent)
        } catch (e: IllegalStateException) {
            if (freshest !== activityLauncher) activityLauncher.launch(intent) else throw e
        }
    }

    fun startPayment(request: PaymentRequest) {
        // Nothing to run a payment on: report it rather than claim the slot and dispatch into the
        // void. Checked before the slot so a shut-down SDK cannot leave the slot claimed.
        val active = graphProvider() ?: run {
            coordinator.deliverNotInitialized()
            return
        }
        // One payment at a time: a second call while one is running gets an immediate
        // ALREADY_IN_PROGRESS callback and cannot disturb the running payment's state.
        if (!coordinator.tryBeginAttempt()) {
            coordinator.rejectConcurrentAttempt()
            return
        }
        // The graph's scope, not the activity's: the ISV activity can be destroyed while the
        // terminal app is in front, and this flow must still run to completion (deliver a result
        // or persist the session) rather than dying with it. The scope is main-dispatched.
        active.sdkScope.launch {
            var posTransactionId: String? = null
            try {
                val ctx = graph.terminalContext()
                if (ctx.requiresPricingType && request.pricingType == null) {
                    coordinator.deliverPreLaunchFailure(
                        PaymentResult.Error(
                            ErrorReason.UNKNOWN,
                            "Merchant uses Dual Pricing; pricingType (CARD/CASH) is required.",
                        ),
                    )
                    return@launch
                }

                // 1) Amounts. Only call calculate-amount when there's actually math to do (ZCP,
                //    tip, or an explicit pricing type) — per the epic ("calculate only if needed").
                //    For a None-ZCP flat sale the amount IS the base, so we skip the call.
                val needsCalc = ctx.zeroCostOption != ZeroCostOption.NONE ||
                    request.tipAmount != null || request.tipRatePercent != null || request.pricingType != null
                val amounts = if (needsCalc) {
                    // calculate-amount is a DISPLAY projection for the terminal's notification — the
                    // create body carries only baseAmount + pricingType and the backend computes the
                    // real per-tender totals, then the SDK re-fetches the canonical outcome.
                    try {
                        graph.calculateAmount(
                            baseAmount = request.baseAmount,
                            currencyCode = ctx.currencyCode,
                            pricingType = request.pricingType?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                            tipAmount = request.tipAmount,
                            tipRate = request.tipRatePercent,
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        // Best-effort is fine for availability, never for money display. A flat
                        // fallback is EXACT only when no ZCP math applies (None + tips the terminal
                        // recomputes itself). For dual-pricing/surcharge/cash-discount the terminal
                        // would display totals that don't match the charge (observed live: an
                        // intended $12.34 card price charged as $12.71) — fail fast instead.
                        if (!flatFallbackIsExact(ctx.zeroCostOption)) {
                            val ex = t.toFluteException()
                            coordinator.deliverPreLaunchFailure(
                                PaymentResult.Error(
                                    ErrorReason.TRANSACTION_CREATION_FAILED,
                                    "Amounts service unavailable — cannot show correct ${ctx.zeroCostOption} totals on the terminal. Try again.",
                                    null,
                                    ex.details?.correlationId,
                                ),
                            )
                            return@launch
                        }
                        CalculatedAmounts.flat(request.baseAmount, ctx.currencyCode)
                    }
                } else {
                    CalculatedAmounts.flat(request.baseAmount, ctx.currencyCode)
                }

                // 2) Create the canonical POS transaction record.
                val ref = graph.createPosTransaction(request, ctx.terminalId, ctx.currencyCode, amounts)
                posTransactionId = ref.posTransactionId
                coordinator.beginSession(ref.posTransactionId)

                // 3) Launch the deeplink carrying the notification payload.
                val intent = DeeplinkIntents.buildLaunchIntent(ref)
                if (intent.resolveActivity(appContext.packageManager) == null) {
                    coordinator.deliverPreLaunchFailure(
                        PaymentResult.Error(
                            ErrorReason.APP_NOT_INSTALLED,
                            "Flute Terminal app is not installed or cannot handle the payment deeplink.",
                            posTransactionId,
                        ),
                    )
                    return@launch
                }
                withContext(Dispatchers.Main) { launchTerminal(intent) }
                coordinator.armTimeout()
            } catch (ce: CancellationException) {
                // Scope torn down mid-create (SDK shutdown): nothing can be delivered — free the
                // payment slot so the next attempt isn't rejected forever. If the transaction was
                // already created server-side, the backend's stuck-transaction sweep reclaims it.
                coordinator.releaseAttempt()
                throw ce
            } catch (t: Throwable) {
                val ex = t.toFluteException()
                val reason = when {
                    ex is InvalidPaymentParametersException -> ErrorReason.UNKNOWN
                    posTransactionId == null -> ErrorReason.TRANSACTION_CREATION_FAILED
                    else -> ErrorReason.UNKNOWN
                }
                coordinator.deliverPreLaunchFailure(
                    PaymentResult.Error(reason, ex.message ?: ex.toString(), posTransactionId, ex.details?.correlationId),
                )
            }
        }
    }

    /**
     * Starts an **unreferenced refund** (return without reference): money back to whatever card the
     * customer presents on the terminal, with no originating transaction
     * (`POST /v2/pos/transactions/reversal` without `originalTransactionId`).
     *
     * Runs the same machinery as [startPayment] — one payment/refund at a time, session persisted
     * before launch, watchdog on no-callback, canonical outcome re-fetched from the API — and the
     * outcome arrives on the same callback registered with
     * [FluteTerminal.registerForPaymentResult]. A successful refund is reported as
     * [PaymentResult.Approved] with `transactionType` identifying it as the refund.
     *
     * No `pricingType` and no calculate-amount call: the ISV states the exact amount to return, so
     * there is no card-vs-cash price to disambiguate and the backend applies no ZCP math to a refund.
     */
    fun startRefund(request: RefundRequest) {
        val active = graphProvider() ?: run {
            coordinator.deliverNotInitialized()
            return
        }
        if (!coordinator.tryBeginAttempt()) {
            coordinator.rejectConcurrentAttempt()
            return
        }
        active.sdkScope.launch {
            var posTransactionId: String? = null
            try {
                val ctx = graph.terminalContext()
                // referenceId is mandatory for a refund (it is the only reconciliation handle) —
                // generate one when the ISV doesn't supply it.
                val reference = request.referenceId ?: "refund-${System.currentTimeMillis()}"

                val ref = graph.createUnreferencedRefund(request, ctx.terminalId, ctx.currencyCode, reference)
                posTransactionId = ref.posTransactionId
                coordinator.beginSession(ref.posTransactionId)

                val intent = DeeplinkIntents.buildLaunchIntent(ref)
                if (intent.resolveActivity(appContext.packageManager) == null) {
                    coordinator.deliverPreLaunchFailure(
                        PaymentResult.Error(
                            ErrorReason.APP_NOT_INSTALLED,
                            "Flute Terminal app is not installed or cannot handle the payment deeplink.",
                            posTransactionId,
                        ),
                    )
                    return@launch
                }
                withContext(Dispatchers.Main) { launchTerminal(intent) }
                coordinator.armTimeout()
            } catch (ce: CancellationException) {
                coordinator.releaseAttempt()
                throw ce
            } catch (t: Throwable) {
                val ex = t.toFluteException()
                val reason = when {
                    ex is InvalidPaymentParametersException -> ErrorReason.UNKNOWN
                    posTransactionId == null -> ErrorReason.TRANSACTION_CREATION_FAILED
                    else -> ErrorReason.UNKNOWN
                }
                coordinator.deliverPreLaunchFailure(
                    PaymentResult.Error(reason, ex.message ?: ex.toString(), posTransactionId, ex.details?.correlationId),
                )
            }
        }
    }

    internal companion object {
        /**
         * The money-display policy: flat display amounts (every tender = base) are exact only when
         * the merchant has no ZCP math. Anything else must not launch the terminal with a display
         * that won't match the charge.
         */
        fun flatFallbackIsExact(zeroCostOption: ZeroCostOption): Boolean =
            zeroCostOption == ZeroCostOption.NONE
    }
}
