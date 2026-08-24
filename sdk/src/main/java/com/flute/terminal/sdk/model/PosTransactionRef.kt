package com.flute.terminal.sdk.model

/** Lightweight reference to a created POS transaction (the payment intent). */
data class PosTransactionRef(
    val posTransactionId: String,
    val terminalId: String?,
    val status: String?,
    /**
     * The raw create-response JSON, relayed to the terminal as the deeplink notification payload
     * (the same envelope the gateway websocket delivers). Not for ISV consumption.
     */
    val notificationPayloadJson: String,
)
