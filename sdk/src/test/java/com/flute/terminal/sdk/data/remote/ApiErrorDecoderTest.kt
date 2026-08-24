package com.flute.terminal.sdk.data.remote

import com.flute.terminal.sdk.exception.FluteApiException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorDecoderTest {

    /** Exact envelope returned by UAT on POST /v2/pos/transactions with an offline terminal. */
    private val uatEnvelope = """
        {
          "Errors": { "ConnectionStatus": ["Terminal Connection status should be Online but was Offline."] },
          "StatusCode": 400,
          "Source": "PosService",
          "ExceptionType": "ValidationException",
          "CorrelationId": "05420191-c6a5-4f9d-9648-620ea0298d75",
          "ErrorCode": "V0000",
          "Title": "Validation failed",
          "Cause": "One or more fields failed validation rules.",
          "Resolution": "Review the errors and correct the invalid fields.",
          "DocumentationUrl": "https://developer.flute.com/"
        }
    """.trimIndent()

    private fun httpException(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `decodes the live UAT validation envelope`() {
        val details = ApiErrorDecoder.decode(httpException(400, uatEnvelope))

        assertEquals(400, details.httpStatus)
        assertEquals("V0000", details.errorCode)
        assertEquals("05420191-c6a5-4f9d-9648-620ea0298d75", details.correlationId)
        assertEquals("PosService", details.source)
        assertEquals("Validation failed", details.title)
        assertEquals(
            listOf("Terminal Connection status should be Online but was Offline."),
            details.fieldErrors["ConnectionStatus"],
        )
        // summary() surfaces the actionable field error, not the generic title
        assertEquals("Terminal Connection status should be Online but was Offline.", details.summary())
    }

    @Test
    fun `non-JSON body still yields details with http status`() {
        val details = ApiErrorDecoder.decode(httpException(502, "<html>Bad Gateway</html>"))
        assertEquals(502, details.httpStatus)
        assertEquals(emptyMap<String, List<String>>(), details.fieldErrors)
        assertTrue(details.summary().contains("502"))
    }

    @Test
    fun `apiCall wraps HttpException into FluteApiException carrying details`() {
        val thrown = assertThrows(FluteApiException::class.java) {
            runBlocking { apiCall<Unit> { throw httpException(400, uatEnvelope) } }
        }
        assertEquals("Terminal Connection status should be Online but was Offline.", thrown.message)
        assertEquals("05420191-c6a5-4f9d-9648-620ea0298d75", thrown.details?.correlationId)
    }

    @Test
    fun `apiCall passes non-HTTP failures through untouched`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { apiCall<Unit> { throw IllegalStateException("io") } }
        }
        assertEquals("io", thrown.message)
    }

    /** Live body from /oauth2/token when UAT credentials are used against DEV. */
    private val oauthInvalidClient = """
        {
          "error": "invalid_client",
          "error_description": "The specified 'client_id' is invalid.",
          "error_uri": "https://documentation.openiddict.com/errors/ID2052"
        }
    """.trimIndent()

    @Test
    fun `decodes the OAuth error body so wrong-environment credentials say why`() {
        val details = ApiErrorDecoder.decode(httpException(401, oauthInvalidClient))

        assertEquals(401, details.httpStatus)
        assertEquals("invalid_client", details.errorCode)
        // Without this the summary would be the useless "API request failed (HTTP 401)".
        assertEquals("The specified 'client_id' is invalid.", details.summary())
    }
}
