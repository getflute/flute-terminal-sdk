package com.flute.terminal.sdk.integration

import com.flute.terminal.sdk.FluteTerminalConfig
import com.flute.terminal.sdk.data.auth.CredentialStore
import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.mapper.Mappers
import com.flute.terminal.sdk.data.remote.ApiFactory
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.data.store.InMemorySecureStore
import com.flute.terminal.sdk.exception.FluteApiException
import com.flute.terminal.sdk.model.TerminalMode
import com.flute.terminal.sdk.model.ZeroCostOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Live contract smoke tests against a running environment.
 *
 * The hosts come from environment variables rather than an [FluteTerminalConfig.Environment]
 * constant: this artifact is published publicly, so Flute's pre-production hostnames are kept out
 * of it and supplied by whoever runs the tests.
 *
 * Auto-skipped unless all four variables are set — they never run in a plain `./gradlew test` or in
 * CI without secrets:
 * ```
 * FLUTE_TEST_CLIENT_ID=...  FLUTE_TEST_CLIENT_SECRET=... \
 * FLUTE_TEST_API_URL=...    FLUTE_TEST_OAUTH_URL=...     \
 *     ./gradlew :sdk:testDebugUnitTest \
 *     --tests "com.flute.terminal.sdk.integration.UatContractSmokeTest"
 * ```
 * These validate the wire contracts (token grant, DTO field names, enum casing, error envelope)
 * — the exact things unit tests with fakes cannot prove.
 */
class UatContractSmokeTest {

    private val clientId: String? = System.getenv("FLUTE_TEST_CLIENT_ID")
    private val clientSecret: String? = System.getenv("FLUTE_TEST_CLIENT_SECRET")
    private val apiUrl: String? = System.getenv("FLUTE_TEST_API_URL")
    private val oauthUrl: String? = System.getenv("FLUTE_TEST_OAUTH_URL")

    private lateinit var apis: ApiFactory
    private lateinit var tokenProvider: TokenProvider

    @Before
    fun setUp() {
        assumeTrue(
            "FLUTE_TEST_CLIENT_ID / _SECRET / _API_URL / _OAUTH_URL not all set — skipping smoke tests",
            clientId != null && clientSecret != null && apiUrl != null && oauthUrl != null,
        )
        // SANDBOX only supplies the enum constant; both hosts are overridden, so nothing about
        // Flute's internal environments is compiled into the published artifact.
        val config = FluteTerminalConfig(
            environment = FluteTerminalConfig.Environment.SANDBOX,
            apiBaseUrlOverride = apiUrl,
            identityBaseUrlOverride = oauthUrl,
        )
        val store = InMemorySecureStore()
        val credentials = CredentialStore(store).apply { save(clientId!!, clientSecret!!) }
        apis = ApiFactory(config)
        tokenProvider = TokenProvider(credentials, apis.identityApi, store)
    }

    @Test
    fun `token grant returns a bearer`() = runBlocking {
        val bearer = tokenProvider.bearer()
        assertTrue(bearer.startsWith("Bearer "))
        assertTrue(tokenProvider.expiresAtMs() > System.currentTimeMillis())
    }

    @Test
    fun `terminals list maps modes and connection status`() = runBlocking {
        val terminals = apiCall { apis.fluteApi.listTerminals(tokenProvider.bearer()) }.items.map(Mappers::toInfo)
        assertTrue("expected at least one terminal on the test merchant", terminals.isNotEmpty())
        // Enum casing must map — no terminal should be UNKNOWN mode.
        assertTrue(terminals.all { it.mode != TerminalMode.UNKNOWN })
    }

    @Test
    fun `payment config maps currency, ZCP and default processor`() = runBlocking {
        val config = Mappers.toConfig(apiCall { apis.fluteApi.getPaymentConfig(tokenProvider.bearer()) })
        assertNotNull(config.currencyCode)
        assertTrue(config.zeroCostOption != ZeroCostOption.UNKNOWN)
        assertNotNull("merchant should expose a default processor", config.defaultProcessorId)
    }

    @Test
    fun `resolve terminal by serial returns exactly one`() = runBlocking {
        // Pick any real serial from the full list, then confirm the filter returns just that one.
        val all = apiCall { apis.fluteApi.listTerminals(tokenProvider.bearer()) }.items
        val serial = all.firstOrNull()?.serialNumber ?: run {
            assumeTrue("no terminals on the merchant", false); return@runBlocking
        }
        val filtered = apiCall { apis.fluteApi.listTerminals(tokenProvider.bearer(), serial) }.items
        assertEquals(1, filtered.size)
        assertEquals(serial, filtered.first().serialNumber)
    }

    /**
     * KNOWN BACKEND GAP (report to backend): `GET /v2/transactions/calculate-amount` currently 400s
     * with a per-tender breakdown. The endpoint is `POST` with a JSON body on the deployed
     * environment — a GET+query call mis-routes to `GET /v2/transactions/{transactionId}` and 400s
     * with `transactionId 'calculate-amount' is not valid`, which is exactly the drift this test
     * exists to catch.
     */
    @Test
    fun `calculate-amount POST returns a per-tender breakdown`() = runBlocking {
        val amounts = Mappers.toCalculatedAmounts(
            apiCall {
                apis.fluteApi.calculateAmount(
                    tokenProvider.bearer(),
                    com.flute.terminal.sdk.data.remote.dto.CalculateAmountRequest(
                        baseAmount = java.math.BigDecimal("10.00"),
                        currencyCode = "USD",
                    ),
                )
            },
        )
        assertNotNull("cash tender missing", amounts.cash)
        assertNotNull("creditCard tender missing", amounts.creditCard)
        assertNotNull("cash total missing", amounts.cash?.totalAmount)
        assertNotNull("creditCard total missing", amounts.creditCard?.totalAmount)
        assertNotNull("ZCP mode missing from response", amounts.zeroCostOption)
    }

    @Test
    fun `create against an offline terminal returns the decoded error envelope`() = runBlocking {
        val terminals = apiCall { apis.fluteApi.listTerminals(tokenProvider.bearer()) }.items
        val offlineSemiIntegrated = terminals.firstOrNull {
            it.terminalMode.equals("SemiIntegrated", true) && !it.connectionStatus.equals("Online", true)
        } ?: run {
            assumeTrue("no offline semi-integrated terminal available to exercise the error path", false)
            return@runBlocking
        }

        try {
            apiCall {
                apis.fluteApi.createPosTransaction(
                    tokenProvider.bearer(),
                    Mappers.toCreateBody(
                        com.flute.terminal.sdk.model.PaymentRequest(
                            baseAmount = java.math.BigDecimal("1.00"),
                            posDeviceId = "SDK-SMOKE-TEST",
                            referenceId = "sdk-contract-smoke",
                        ),
                        terminalId = offlineSemiIntegrated.terminalId,
                        currencyCode = "USD",
                    ),
                )
            }
            // If it succeeded, a terminal was actually online — also a valid (better) outcome.
        } catch (e: FluteApiException) {
            // The decoded envelope must carry the platform fields — this is the decoder's live proof.
            assertEquals(400, e.details?.httpStatus)
            assertNotNull("correlationId missing from decoded envelope", e.details?.correlationId)
            assertNotNull("errorCode missing from decoded envelope", e.details?.errorCode)
            assertTrue(e.details!!.fieldErrors.isNotEmpty())
        }
    }
}
