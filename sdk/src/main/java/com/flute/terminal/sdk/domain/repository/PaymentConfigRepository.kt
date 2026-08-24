package com.flute.terminal.sdk.domain.repository

import com.flute.terminal.sdk.model.PaymentConfig

/** Merchant payment configuration resource. Pure domain contract — no Android/Retrofit leaks. */
internal interface PaymentConfigRepository {
    /** Fetches from the API and persists the result. */
    suspend fun get(): PaymentConfig

    /** Last persisted config, or null if never fetched. Survives process restarts. */
    fun cached(): PaymentConfig?
}
