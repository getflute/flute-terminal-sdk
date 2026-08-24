package com.flute.terminal.sdk.data.repository

import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.mapper.Mappers
import com.flute.terminal.sdk.data.remote.FluteApi
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.data.store.SecureStore
import com.flute.terminal.sdk.data.store.StoreKeys
import com.flute.terminal.sdk.domain.repository.PaymentConfigRepository
import com.flute.terminal.sdk.model.PaymentConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class PaymentConfigRepositoryImpl(
    private val api: FluteApi,
    private val tokenProvider: TokenProvider,
    private val store: SecureStore,
    private val gson: Gson,
    private val io: CoroutineDispatcher,
) : PaymentConfigRepository {

    override suspend fun get(): PaymentConfig = withContext(io) {
        val config = Mappers.toConfig(apiCall { api.getPaymentConfig(tokenProvider.bearer()) })
        store.putString(StoreKeys.PAYMENT_CONFIG, gson.toJson(config))
        config
    }

    override fun cached(): PaymentConfig? =
        store.getString(StoreKeys.PAYMENT_CONFIG)?.let {
            runCatching { gson.fromJson(it, PaymentConfig::class.java) }.getOrNull()
        }
}
