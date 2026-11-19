package com.flute.terminal.sdk.data.repository

import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.mapper.Mappers
import com.flute.terminal.sdk.data.remote.FluteApi
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.data.remote.dto.PaymentConfigResponse
import com.flute.terminal.sdk.data.store.SecureStore
import com.flute.terminal.sdk.data.store.StoreKeys
import com.flute.terminal.sdk.domain.repository.PaymentConfigRepository
import com.flute.terminal.sdk.model.PaymentConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException

internal class PaymentConfigRepositoryImpl(
    private val api: FluteApi,
    private val tokenProvider: TokenProvider,
    private val store: SecureStore,
    private val gson: Gson,
    private val io: CoroutineDispatcher,
) : PaymentConfigRepository {

    override suspend fun get(): PaymentConfig = withContext(io) {
        val bearer = tokenProvider.bearer()
        val response = apiCall {
            fetchWithLegacyFallback(
                current = { api.getPaymentConfiguration(bearer) },
                legacy = { api.getLegacyPaymentConfig(bearer) },
            )
        }
        val config = Mappers.toConfig(response)
        store.putString(StoreKeys.PAYMENT_CONFIG, gson.toJson(config))
        config
    }

    override fun cached(): PaymentConfig? =
        store.getString(StoreKeys.PAYMENT_CONFIG)?.let {
            runCatching { gson.fromJson(it, PaymentConfig::class.java) }.getOrNull()
        }
}

/**
 * Calls [current]; only when it answers 404 (the route does not exist on this gateway) calls
 * [legacy]. If the legacy route is missing too, the 404 from [current] is rethrown, because it
 * carries the error from the endpoint integrators are documented against.
 */
internal suspend fun fetchWithLegacyFallback(
    current: suspend () -> PaymentConfigResponse,
    legacy: suspend () -> PaymentConfigResponse,
): PaymentConfigResponse = try {
    current()
} catch (e: HttpException) {
    if (e.code() != 404) throw e
    try {
        legacy()
    } catch (legacyError: HttpException) {
        if (legacyError.code() == 404) throw e.apply { addSuppressed(legacyError) } else throw legacyError
    }
}
