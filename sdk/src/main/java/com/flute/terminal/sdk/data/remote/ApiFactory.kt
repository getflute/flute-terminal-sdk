package com.flute.terminal.sdk.data.remote

import com.flute.terminal.sdk.FluteTerminalConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Builds the Retrofit-backed remote APIs for a given config. Pure data-layer wiring. */
internal class ApiFactory(private val config: FluteTerminalConfig) {

    // The HTTP logger is the SDK's only logcat writer, and its bodies carry credentials and
    // tokens — so in PRODUCTION it stays off no matter what the ISV passes. The flag is honored
    // only in non-production environments (QA/debug builds against UAT/sandbox).
    private val httpLoggingAllowed =
        config.enableHttpLogging && config.environment != FluteTerminalConfig.Environment.PRODUCTION

    private val httpClient: OkHttpClient = OkHttpClient.Builder().apply {
        // OkHttp defaults to 10s, which is too tight here: terminals run on shop wifi and the
        // platform can slow down (observed live: a 9s token call, and payment-config — the heaviest
        // query — timing out at 10s while other endpoints answered in ~1.5s). Losing a sale to a
        // slow-but-working backend is worse than waiting a few more seconds.
        connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Retries idempotent GETs only — never a create/reversal POST (see RetryInterceptor).
        addInterceptor(RetryInterceptor())
        if (httpLoggingAllowed) {
            addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        }
    }.build()

    val identityApi: FluteIdentityApi = retrofit(config.identityBaseUrl).create(FluteIdentityApi::class.java)
    val fluteApi: FluteApi = retrofit(config.apiBaseUrl).create(FluteApi::class.java)

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl.ensureTrailingSlash())
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private fun String.ensureTrailingSlash() = if (endsWith("/")) this else "$this/"

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}
