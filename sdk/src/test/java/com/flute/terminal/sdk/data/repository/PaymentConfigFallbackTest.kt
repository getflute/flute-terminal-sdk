package com.flute.terminal.sdk.data.repository

import com.flute.terminal.sdk.data.remote.dto.PaymentConfigResponse
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** The current route is used first; the legacy route only when the current one answers 404. */
class PaymentConfigFallbackTest {

    private fun response(currency: String) = PaymentConfigResponse(
        currencyCode = currency,
        merchantCompanyName = null,
        zeroCostProcessingOption = "None",
        isTipsEnabled = null,
        defaultTipOptions = null,
        availablePaymentProcessors = null,
    )

    private fun http(code: Int) = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `uses the current route and never calls legacy when it succeeds`() = runBlocking {
        var legacyCalls = 0
        val result = fetchWithLegacyFallback(
            current = { response("USD") },
            legacy = { legacyCalls++; response("EUR") },
        )
        assertEquals("USD", result.currencyCode)
        assertEquals(0, legacyCalls)
    }

    @Test
    fun `falls back to legacy when the current route returns 404`() = runBlocking {
        val result = fetchWithLegacyFallback(
            current = { throw http(404) },
            legacy = { response("EUR") },
        )
        assertEquals("EUR", result.currencyCode)
    }

    @Test
    fun `does not fall back on errors other than 404`() {
        var legacyCalls = 0
        val error = http(500)
        val thrown = assertThrows(HttpException::class.java) {
            runBlocking {
                fetchWithLegacyFallback(current = { throw error }, legacy = { legacyCalls++; response("EUR") })
            }
        }
        assertSame(error, thrown)
        assertEquals(0, legacyCalls)
    }

    @Test
    fun `rethrows the current route's 404 when legacy is missing too`() {
        val currentError = http(404)
        val thrown = assertThrows(HttpException::class.java) {
            runBlocking {
                fetchWithLegacyFallback(current = { throw currentError }, legacy = { throw http(404) })
            }
        }
        assertSame(currentError, thrown)
    }

    @Test
    fun `surfaces a non-404 legacy failure`() {
        val legacyError = http(401)
        val thrown = assertThrows(HttpException::class.java) {
            runBlocking {
                fetchWithLegacyFallback(current = { throw http(404) }, legacy = { throw legacyError })
            }
        }
        assertSame(legacyError, thrown)
    }
}
