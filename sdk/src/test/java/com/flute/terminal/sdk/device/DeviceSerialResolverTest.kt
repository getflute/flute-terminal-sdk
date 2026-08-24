package com.flute.terminal.sdk.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceSerialResolverTest {

    @Test
    fun `picks the first real serial and trims dashes like the terminal app`() {
        assertEquals(
            "P21222AB20001",
            DeviceSerialResolver.firstUsable(listOf(null, "", "P21222-AB2-0001", "OTHER")),
        )
    }

    @Test
    fun `rejects platform junk values`() {
        assertNull(DeviceSerialResolver.firstUsable(listOf(null, "", "  ", "unknown", "UNKNOWN", "EMULATOR34X1B0")))
    }

    @Test
    fun `empty candidate list resolves to null`() {
        assertNull(DeviceSerialResolver.firstUsable(emptyList()))
    }

    // The terminal app's serial is authoritative: it is the value the app registered the terminal
    // with, so it must beat anything scraped from the platform even when the platform also answers.
    @Test
    fun `terminal app serial wins over system properties`() {
        assertEquals(
            "713270155",
            DeviceSerialResolver.candidates(terminalApp = "713-270-155", platform = listOf("SOMETHINGELSE")),
        )
    }

    @Test
    fun `falls back to the platform when the terminal app answers with nothing`() {
        assertEquals(
            "P21222AB20001",
            DeviceSerialResolver.candidates(terminalApp = null, platform = listOf("P21222-AB2-0001")),
        )
        assertEquals(
            "P21222AB20001",
            DeviceSerialResolver.candidates(terminalApp = "", platform = listOf("P21222-AB2-0001")),
        )
    }

    // Android 10+ with a terminal app too old to publish the serial: nothing is resolvable, and the
    // caller has to say so rather than silently paying on the wrong terminal.
    @Test
    fun `resolves to null when neither the terminal app nor the platform answers`() {
        assertNull(DeviceSerialResolver.candidates(terminalApp = null, platform = listOf(null, "unknown")))
    }
}
