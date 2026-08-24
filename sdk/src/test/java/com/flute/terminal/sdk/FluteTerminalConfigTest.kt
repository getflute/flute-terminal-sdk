package com.flute.terminal.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluteTerminalConfigTest {

    /**
     * Sandbox and production are one deployment separated by hostname — the API key's account kind
     * is checked against the surface it is used on. Pointing sandbox at a production host (or
     * sharing production's OAuth host) would make every sandbox key fail authorization, so the
     * pairing is pinned here.
     */
    @Test
    fun `sandbox and production are distinct partner surfaces`() {
        val sandbox = FluteTerminalConfig.Environment.SANDBOX
        val production = FluteTerminalConfig.Environment.PRODUCTION

        assertEquals("https://sandbox.api.flute.com", sandbox.apiBaseUrl)
        assertEquals("https://sandbox.oauth.api.flute.com", sandbox.identityBaseUrl)
        assertEquals("https://api.flute.com", production.apiBaseUrl)
        assertEquals("https://oauth.api.flute.com", production.identityBaseUrl)
    }

    @Test
    fun `every environment is https and has both hosts`() {
        FluteTerminalConfig.Environment.values().forEach { env ->
            assertTrue("${env.name} api must be https", env.apiBaseUrl.startsWith("https://"))
            assertTrue("${env.name} identity must be https", env.identityBaseUrl.startsWith("https://"))
        }
    }

    /** An override replaces the environment's host; without one the environment's host stands. */
    @Test
    fun `overrides win over the environment hosts`() {
        val defaulted = FluteTerminalConfig(environment = FluteTerminalConfig.Environment.SANDBOX)
        assertEquals("https://sandbox.api.flute.com", defaulted.apiBaseUrl)

        val overridden = FluteTerminalConfig(
            environment = FluteTerminalConfig.Environment.SANDBOX,
            apiBaseUrlOverride = "https://api.test.local",
            identityBaseUrlOverride = "https://oauth.test.local",
        )
        assertEquals("https://api.test.local", overridden.apiBaseUrl)
        assertEquals("https://oauth.test.local", overridden.identityBaseUrl)
    }
}
