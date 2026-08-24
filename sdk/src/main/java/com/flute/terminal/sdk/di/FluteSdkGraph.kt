package com.flute.terminal.sdk.di

import android.content.Context
import com.flute.terminal.sdk.FluteLogLevel
import com.flute.terminal.sdk.FluteTerminalConfig
import com.flute.terminal.sdk.data.auth.CredentialStore
import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.auth.TokenRefreshScheduler
import com.flute.terminal.sdk.data.remote.ApiFactory
import com.flute.terminal.sdk.data.repository.PaymentConfigRepositoryImpl
import com.flute.terminal.sdk.data.repository.PosTransactionRepositoryImpl
import com.flute.terminal.sdk.data.repository.TerminalRepositoryImpl
import com.flute.terminal.sdk.data.store.EncryptedSecureStore
import com.flute.terminal.sdk.data.store.PaymentSessionStore
import com.flute.terminal.sdk.data.store.StoreKeys
import com.flute.terminal.sdk.device.DeviceSerialResolver
import com.flute.terminal.sdk.domain.usecase.CalculateAmountUseCase
import com.flute.terminal.sdk.domain.usecase.CreatePosTransactionUseCase
import com.flute.terminal.sdk.domain.usecase.GetPaymentConfigUseCase
import com.flute.terminal.sdk.domain.usecase.GetTerminalsUseCase
import com.flute.terminal.sdk.domain.usecase.ResolvePaymentOutcomeUseCase
import com.flute.terminal.sdk.exception.InvalidPaymentParametersException
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manual dependency graph built once by [com.flute.terminal.sdk.FluteTerminal.initialize].
 * Deliberately not Hilt/Dagger — an SDK must not force a DI framework on the consuming app.
 * Wiring flows one way: store/remote → data(repos) → domain(use cases); the facade reads use cases.
 */
internal class FluteSdkGraph(
    context: Context,
    val config: FluteTerminalConfig,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Scope for facade-initiated async work not tied to an Activity (warm-up, discovery, refresh).
     * Main-dispatched, which is what makes the SDK's "callbacks arrive on the main thread" contract
     * hold — see the callback boundaries in [com.flute.terminal.sdk.FluteTerminal].
     */
    val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Emits a redacted, structured diagnostic to the ISV-supplied logger (no-op if none). */
    fun log(level: FluteLogLevel, message: String, error: Throwable? = null) {
        config.logger?.log(level, message, error)
    }

    // Environment-scoped: see NamespacedSecureStore. Switching environments must never reuse the
    // previous one's token, config, terminalId or pending session.
    private val store = com.flute.terminal.sdk.data.store.NamespacedSecureStore(
        EncryptedSecureStore(context.applicationContext),
        config.environment.name,
    )
    private val gson = Gson()
    private val apis = ApiFactory(config)

    val credentials = CredentialStore(store)
    val sessionStore = PaymentSessionStore(store)
    private val tokenProvider = TokenProvider(credentials, apis.identityApi, store)
    private val tokenRefreshScheduler = TokenRefreshScheduler(sdkScope, tokenProvider)

    private val posTransactionRepository = PosTransactionRepositoryImpl(apis.fluteApi, tokenProvider, gson, io)
    private val terminalRepository = TerminalRepositoryImpl(apis.fluteApi, tokenProvider, io)
    private val paymentConfigRepository = PaymentConfigRepositoryImpl(apis.fluteApi, tokenProvider, store, gson, io)
    /** Post-payment operations (capture / reversal / tip / receipts / lookup). */
    val transactions: com.flute.terminal.sdk.domain.repository.TransactionRepository =
        com.flute.terminal.sdk.data.repository.TransactionRepositoryImpl(apis.fluteApi, tokenProvider, io)

    val calculateAmount = CalculateAmountUseCase(posTransactionRepository)
    val createPosTransaction = CreatePosTransactionUseCase(posTransactionRepository)
    val createUnreferencedRefund =
        com.flute.terminal.sdk.domain.usecase.CreateUnreferencedRefundUseCase(posTransactionRepository)
    val getTerminals = GetTerminalsUseCase(terminalRepository)
    val getPaymentConfig = GetPaymentConfigUseCase(paymentConfigRepository)
    val resolvePaymentOutcome = ResolvePaymentOutcomeUseCase(posTransactionRepository)

    /**
     * The serial identifying this terminal to the backend: an explicit config value wins (dev,
     * emulator, a device the SDK cannot resolve); otherwise it comes from the terminal app, or
     * failing that the platform — so on real hardware the ISV configures nothing and the terminalId
     * resolves, and persists, automatically. See [DeviceSerialResolver].
     */
    val serialNumber: String? = config.serialNumber ?: DeviceSerialResolver.read(context)

    @Volatile private var cachedTerminalId: String? = null
    @Volatile private var cachedCurrency: String? = null

    /** Resolved device context: terminalId (from serial), currency, and the merchant ZCP mode. */
    data class TerminalContext(
        val terminalId: String,
        val currencyCode: String,
        val requiresPricingType: Boolean,
        val zeroCostOption: com.flute.terminal.sdk.model.ZeroCostOption,
    )

    /**
     * Resolves this device's terminal (by serial) and the merchant currency, caching both. Throws
     * a typed error if no serial was configured or no terminal matches — the ISV can't pay without it.
     */
    suspend fun terminalContext(): TerminalContext {
        val serial = serialNumber
            ?: throw InvalidPaymentParametersException(
                "No device serial available: the Flute Terminal app did not provide one (not " +
                    "installed, or too old to publish it), the platform hides it on Android 10+, " +
                    "and no serialNumber was configured.",
            )
        val terminalId = resolveTerminalId(serial)
            ?: throw InvalidPaymentParametersException("No terminal found for serial $serial.")
        val config = getPaymentConfig.cached() ?: getPaymentConfig()
        cachedCurrency = config.currencyCode
        val currency = config.currencyCode
            ?: throw InvalidPaymentParametersException("Merchant has no configured currency.")
        return TerminalContext(terminalId, currency, config.requiresPricingType, config.zeroCostOption)
    }

    /**
     * serial → terminalId, cheapest source first: memory, then the encrypted store (survives
     * restarts; invalidated when the serial changes), then the API — persisting a fresh resolution.
     */
    private suspend fun resolveTerminalId(serial: String): String? {
        cachedTerminalId?.let { return it }
        store.getString(StoreKeys.TERMINAL_ID)
            ?.takeIf { store.getString(StoreKeys.TERMINAL_ID_SERIAL) == serial }
            ?.let { cachedTerminalId = it; return it }
        return getTerminals.bySerial(serial)?.id?.also {
            cachedTerminalId = it
            store.putString(StoreKeys.TERMINAL_ID, it)
            store.putString(StoreKeys.TERMINAL_ID_SERIAL, serial)
        }
    }

    /** This device's terminalId, resolving it if needed — print-receipt must name a terminal. */
    suspend fun requireTerminalId(): String = terminalContext().terminalId

    /** ISV-initiated cancel of an in-flight POS transaction (throws once it's already terminal). */
    suspend fun cancelPosTransaction(posTransactionId: String) =
        posTransactionRepository.cancel(posTransactionId)

    @Volatile private var warmedUp = false

    /**
     * Warm-up run at initialize(): ensure a usable token, a *fresh* payment config, and the
     * terminal id, then arm the refresh loop. Runs its network work **once per graph** — a graph is
     * built only on first init, a config change, or a process restart, and reused across Activity
     * recreations (see [FluteTerminal.initialize]), so this is not per-payment work.
     *
     * Payment config is fetched fresh here (falling back to the persisted copy only if the fetch
     * fails), because merchant settings — notably the ZCP / Dual-Pricing mode that drives
     * [TerminalContext.requiresPricingType] — can change server-side between launches; a config
     * cached indefinitely would make the SDK mis-detect the pricing model. The token, by contrast,
     * is reused (its freshness is the [TokenRefreshScheduler]'s job) so this never re-authenticates.
     */
    fun warmUp(onReady: ((Result<Unit>) -> Unit)?) {
        if (warmedUp) { // reused graph (Activity recreation): nothing to redo
            onReady?.invoke(Result.success(Unit))
            return
        }
        sdkScope.launch {
            val result = runCatching {
                tokenProvider.bearer()          // reuses the persisted token; fetches only if missing/expiring
                refreshPaymentConfig()          // fresh config; falls back to cached on failure
                serialNumber?.let { resolveTerminalId(it) }
                tokenRefreshScheduler.start()   // keep the token warm from here on
            }.map { warmedUp = true }
            onReady?.invoke(result)
        }
    }

    /** Fetches + persists the latest payment config; on network failure keeps the last good one. */
    private suspend fun refreshPaymentConfig() {
        runCatching { paymentConfigRepository.get() }
            .recover { e -> paymentConfigRepository.cached() ?: throw e }
    }

    /**
     * Provision (or replace) the API credentials at runtime, then re-warm. Everything scoped to the
     * old merchant is dropped — token, payment config, resolved terminal — because the new
     * credentials may belong to a different merchant; the warm-up then authenticates fresh (which
     * is also what makes bad credentials fail fast here). This is how credentials entered after
     * startup (device provisioning, ISV backend hand-off, QA) take effect without an app restart.
     */
    fun provisionCredentials(clientId: String, clientSecret: String, onReady: ((Result<Unit>) -> Unit)?) {
        credentials.saveProvisioned(clientId, clientSecret)
        tokenProvider.clear()
        store.remove(StoreKeys.PAYMENT_CONFIG)
        store.remove(StoreKeys.TERMINAL_ID)
        store.remove(StoreKeys.TERMINAL_ID_SERIAL)
        cachedTerminalId = null
        warmedUp = false // force a full re-warm against the new merchant
        warmUp(onReady)
    }

    /** Stops the refresh loop and cancels all of this graph's async work. The graph is dead after. */
    fun shutdown() {
        tokenRefreshScheduler.stop()
        sdkScope.cancel()
    }
}
