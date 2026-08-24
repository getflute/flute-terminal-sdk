package com.flute.terminal.sdk.data.auth

import com.flute.terminal.sdk.data.store.InMemorySecureStore
import com.flute.terminal.sdk.data.store.NamespacedSecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credentials provisioned at runtime must outrank config-supplied ones. An app that passes
 * credentials in its config on every launch (a QA build reading local.properties, say) would
 * otherwise overwrite what an operator just entered on the very next initialize — so provisioning
 * would appear not to save at all.
 */
class CredentialStoreTest {

    @Test
    fun `runtime provisioning is recorded and config seeding is not`() {
        val store = CredentialStore(InMemorySecureStore())

        store.save("config-id", "config-secret")
        assertFalse("config seeding must not claim runtime provenance", store.wasProvisionedAtRuntime())

        store.saveProvisioned("entered-id", "entered-secret")
        assertTrue(store.wasProvisionedAtRuntime())
        assertEquals("entered-id", store.clientId())
    }

    @Test
    fun `clear resets provenance so the next config seed applies again`() {
        val store = CredentialStore(InMemorySecureStore())
        store.saveProvisioned("entered-id", "entered-secret")

        store.clear()

        assertFalse(store.wasProvisionedAtRuntime())
        assertNull(store.clientId())
    }

    @Test
    fun `credentials and their provenance are per environment`() {
        val device = InMemorySecureStore()
        val uat = CredentialStore(NamespacedSecureStore(device, "UAT"))
        val dev = CredentialStore(NamespacedSecureStore(device, "DEV"))

        uat.save("uat-id", "uat-secret")            // seeded from config
        dev.saveProvisioned("dev-id", "dev-secret") // entered by the operator

        assertEquals("uat-id", uat.clientId())
        assertEquals("dev-id", dev.clientId())
        assertFalse("UAT was only seeded", uat.wasProvisionedAtRuntime())
        assertTrue("DEV was provisioned at runtime", dev.wasProvisionedAtRuntime())
    }
}
