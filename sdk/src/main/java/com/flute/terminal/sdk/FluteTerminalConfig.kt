package com.flute.terminal.sdk

/**
 * One-time SDK configuration, supplied to [FluteTerminal.initialize].
 *
 * [clientId]/[clientSecret] are the merchant-scoped API credentials minted for the ISV
 * (IsvApiBff `v2/api-keys`). The resulting OAuth token is **merchant-scoped**, so the merchant
 * identity rides in the token — it is never passed on the create-transaction request body.
 *
 * SECURITY NOTE (ARISE-4289): a client secret living on a shared countertop device is sensitive.
 * The prototype keeps it in memory only. A production build must source it from the Android
 * Keystore / a secure provisioning channel, never bundle it in the APK.
 */
data class FluteTerminalConfig(
    val environment: Environment,
    /**
     * Merchant-scoped API credentials. Supply them **once** (e.g. the provisioning launch); the SDK
     * persists them encrypted and reuses them thereafter, so later launches may pass null. Passing
     * new values re-provisions (overwrites) the stored credentials.
     */
    val clientId: String? = null,
    val clientSecret: String? = null,
    /**
     * Device serial number, used to resolve *this device's* terminalId at init
     * (`GET /v2/terminals?serialNumber=`). Leave null on real Sunmi/Verifone hardware — the SDK
     * reads the serial from the device itself and persists the resolved terminalId. Supply it
     * explicitly only where the platform hides the serial (emulator, Android 10+ non-privileged
     * apps); an explicit value always wins over auto-detection.
     */
    val serialNumber: String? = null,
    /** Overrides the environment's default base URLs. Leave null to use the defaults. */
    val identityBaseUrlOverride: String? = null,
    val apiBaseUrlOverride: String? = null,
    /**
     * Verbose OkHttp request/response **body** logging to Logcat. Bodies contain credentials and
     * tokens, so this is honored ONLY in non-production environments (forced off in PRODUCTION,
     * regardless of this flag) and is intended for local debugging. Prefer [logger] for anything
     * ISVs should see — it emits redacted, structured events.
     */
    val enableHttpLogging: Boolean = false,
    /**
     * Optional sink for the SDK's redacted, structured diagnostics (never credentials/tokens/PANs).
     * Route it into the ISV's logging/telemetry, or leave null for silence.
     */
    val logger: FluteLogger? = null,
    /**
     * How long to wait for the Flute Terminal app to return a result before the SDK resolves the
     * payment itself against the API (covering a killed/crashed terminal app). Card + PIN + tip
     * can legitimately take minutes — keep this generous.
     */
    val terminalResultTimeoutSeconds: Long = 300,
) {
    val identityBaseUrl: String get() = identityBaseUrlOverride ?: environment.identityBaseUrl
    val apiBaseUrl: String get() = apiBaseUrlOverride ?: environment.apiBaseUrl

    /**
     * Target environment.
     *
     * [SANDBOX] and [PRODUCTION] are the two partner-facing surfaces. They are one deployment
     * separated by hostname: the API key carries an account kind, and using a sandbox key on the
     * production host (or the reverse) is rejected. Sandbox is therefore where an ISV integrates
     * without moving real money.
     *
     * [DEV] and [UAT] are internal-only, for Flute QA against the arise-branded deployments while
     * the SDK is in development. They are not offered to ISVs and come out of the published SDK
     * before partner release.
     */
    enum class Environment(val identityBaseUrl: String, val apiBaseUrl: String) {
        /** Internal only. Ahead of UAT — where newly shipped endpoints land first. */
        DEV(
            identityBaseUrl = "https://oauth.api.dev.flute.com",
            apiBaseUrl = "https://api.dev.flute.com",
        ),

        /** Internal only. */
        UAT(
            identityBaseUrl = "https://oauth.api.uat.flute.com",
            apiBaseUrl = "https://api.uat.flute.com",
        ),

        /** Partner integration surface. Requires a sandbox-kind API key; no real money moves. */
        SANDBOX(
            identityBaseUrl = "https://sandbox.oauth.api.flute.com",
            apiBaseUrl = "https://sandbox.api.flute.com",
        ),

        /** Partner live surface. Requires a live-kind API key; cards are charged. */
        PRODUCTION(
            identityBaseUrl = "https://oauth.api.flute.com",
            apiBaseUrl = "https://api.flute.com",
        ),
    }
}
