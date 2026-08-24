package com.flute.terminal.sdk.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Persisted state is environment-specific (a UAT token is rejected by DEV; the same terminal has a
 * different terminalId per environment). These pin that two environments sharing one device store
 * can never read each other's state — the failure mode would be silent cross-environment reuse.
 */
class NamespacedSecureStoreTest {

    private val device = InMemorySecureStore()
    private val uat = NamespacedSecureStore(device, "UAT")
    private val dev = NamespacedSecureStore(device, "DEV")

    @Test
    fun `environments cannot see each other's values`() {
        uat.putString(StoreKeys.ACCESS_TOKEN, "uat-token")
        uat.putString(StoreKeys.TERMINAL_ID, "uat-terminal")

        assertNull("DEV must not see the UAT token", dev.getString(StoreKeys.ACCESS_TOKEN))
        assertNull("DEV must not see the UAT terminalId", dev.getString(StoreKeys.TERMINAL_ID))

        dev.putString(StoreKeys.ACCESS_TOKEN, "dev-token")
        assertEquals("uat-token", uat.getString(StoreKeys.ACCESS_TOKEN))
        assertEquals("dev-token", dev.getString(StoreKeys.ACCESS_TOKEN))
    }

    @Test
    fun `removing in one environment leaves the other intact`() {
        uat.putLong(StoreKeys.TOKEN_EXPIRES_AT, 111L)
        dev.putLong(StoreKeys.TOKEN_EXPIRES_AT, 222L)

        uat.remove(StoreKeys.TOKEN_EXPIRES_AT)

        assertEquals(0L, uat.getLong(StoreKeys.TOKEN_EXPIRES_AT, 0L))
        assertEquals(222L, dev.getLong(StoreKeys.TOKEN_EXPIRES_AT, 0L))
    }

    @Test
    fun `switching away and back keeps the original environment warm`() {
        uat.putString(StoreKeys.PAYMENT_CONFIG, "{uat-config}")
        dev.putString(StoreKeys.PAYMENT_CONFIG, "{dev-config}")

        assertEquals("{uat-config}", uat.getString(StoreKeys.PAYMENT_CONFIG))
    }
}
