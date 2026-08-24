package com.flute.terminal.sdk

/**
 * Sink for the SDK's diagnostic output. Supply one via [FluteTerminalConfig.logger] to route SDK
 * logs into the ISV's own logging/telemetry; leave it null to stay silent.
 *
 * The SDK never logs credentials, bearer tokens, PANs, or full request/response bodies — messages
 * are redacted, structured one-liners (operation, HTTP status, correlation id). This replaces raw
 * HTTP body logging, which is unsafe to emit from a payments SDK.
 */
fun interface FluteLogger {
    fun log(level: FluteLogLevel, message: String, error: Throwable?)
}

enum class FluteLogLevel { DEBUG, INFO, WARN, ERROR }
