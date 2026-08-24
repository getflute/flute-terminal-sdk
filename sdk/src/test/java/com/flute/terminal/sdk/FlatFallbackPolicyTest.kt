package com.flute.terminal.sdk

import com.flute.terminal.sdk.model.ZeroCostOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the money-display policy: when calculate-amount fails, the flat display fallback may only
 * be used where it is EXACT (None). For any ZCP mode the terminal would show totals that don't
 * match the charge (observed live: an intended $12.34 card price charged as $12.71) — the payment
 * must fail fast instead. Loosening this is a money-integrity regression, not an availability win.
 */
class FlatFallbackPolicyTest {

    @Test
    fun `flat fallback is exact only for None`() {
        assertTrue(FluteTerminalLauncher.flatFallbackIsExact(ZeroCostOption.NONE))

        assertFalse(FluteTerminalLauncher.flatFallbackIsExact(ZeroCostOption.DUAL_PRICING))
        assertFalse(FluteTerminalLauncher.flatFallbackIsExact(ZeroCostOption.SURCHARGE))
        assertFalse(FluteTerminalLauncher.flatFallbackIsExact(ZeroCostOption.CASH_DISCOUNT))
        assertFalse(FluteTerminalLauncher.flatFallbackIsExact(ZeroCostOption.UNKNOWN))
    }
}
