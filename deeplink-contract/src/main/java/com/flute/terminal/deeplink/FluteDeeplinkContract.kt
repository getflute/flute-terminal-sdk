package com.flute.terminal.deeplink

import android.content.Intent
import android.net.Uri

/**
 * The app-to-app payment contract between the Flute Terminal SDK and the Flute Terminal app.
 *
 * **Payload = the POS-transaction notification envelope.** The deeplink carries the *same* JSON the
 * gateway pushes to the terminal over websocket today (`PosTransactionTerminalNotification`), under
 * [EXTRA_NOTIFICATION_PAYLOAD]. This is deliberate: the terminal app parses it with its existing
 * `PosTransaction` model and drives its existing `handleTransactionType` flow unchanged — the
 * deeplink is just a second delivery channel for the identical payload. The SDK obtains this
 * payload from the create-transaction API and relays it verbatim.
 *
 * This module is the single shared artifact both codebases compile against — an Intent-key rename
 * here is a compile error on both sides, not a field bug on a merchant counter. Primitive-typed
 * only (Strings): no SDK or terminal-app model types leak in.
 *
 * ⚠️ PROPOSED — the terminal app's matching intent-filter/handler is ARISE-4283; freeze jointly
 * with that team before v1.0.
 */
object FluteDeeplinkContract {

    /** Explicit target package of the Flute Terminal app. */
    const val TERMINAL_PACKAGE = "com.aurora.aurorapayment"

    const val ACTION_START_PAYMENT = "com.flute.terminal.action.START_PAYMENT"
    const val SCHEME = "flute-terminal"

    // ---- Launch (SDK -> Terminal app) extras ----
    /**
     * The POS-transaction notification payload as a JSON string — byte-for-byte the same envelope
     * the terminal receives from the gateway websocket (`PosTransactionTerminalNotification`), so
     * the terminal can feed it straight into its existing notification handling.
     */
    const val EXTRA_NOTIFICATION_PAYLOAD = "notification_payload"

    /** The POS transaction id, duplicated out of the payload for cheap validation/logging. */
    const val EXTRA_POS_TRANSACTION_ID = "pos_transaction_id"

    // ---- Result (Terminal app -> SDK) extras ----
    const val RESULT_STATUS = "result_status"          // APPROVED | DECLINED | ERROR
    const val RESULT_POS_TRANSACTION_ID = "pos_transaction_id"
    const val RESULT_TRANSACTION_ID = "transaction_id"
    const val RESULT_AUTH_CODE = "auth_code"
    const val RESULT_RESPONSE_CODE = "response_code"
    const val RESULT_RECEIPT_DATA = "receipt_data"
    const val RESULT_ERROR_REASON = "error_reason"
    const val RESULT_MESSAGE = "message"

    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_DECLINED = "DECLINED"
    const val STATUS_ERROR = "ERROR"

    // ---- Terminal info (SDK -> Terminal app, read-only) ----

    /**
     * Authority of the terminal app's read-only info provider.
     *
     * The device serial identifies a terminal to the backend, but an ordinary app cannot read it on
     * Android 10+: `android.os.SystemProperties` is off the non-SDK-interface allowlist and
     * `Build.getSerial()` needs a privileged permission. The terminal app has it anyway — it reads
     * the serial through the vendor (Sunmi/Verifone) SDK — so it publishes it here rather than
     * every ISV hand-configuring one.
     */
    const val TERMINAL_INFO_AUTHORITY = "com.aurora.aurorapayment.terminalinfo"

    const val TERMINAL_INFO_PATH_SERIAL = "serial"

    /**
     * Serial column of the [terminalInfoUri] row. The value is normalised the way the terminal app
     * normalises it before registering with the backend — dashes stripped, so `713-270-155` is
     * published as `713270155`. A caller that reformats it will not match any terminal record.
     */
    const val COLUMN_SERIAL_NUMBER = "serial_number"

    /** Content URI of the single-row, single-column serial cursor. */
    @JvmStatic
    fun terminalInfoUri(): Uri = Uri.Builder()
        .scheme("content")
        .authority(TERMINAL_INFO_AUTHORITY)
        .appendPath(TERMINAL_INFO_PATH_SERIAL)
        .build()

    /**
     * Builds the launch Intent. [notificationPayloadJson] is the POS-transaction notification
     * envelope (see [EXTRA_NOTIFICATION_PAYLOAD]); [posTransactionId] is its id, surfaced for
     * validation. The terminal is triggered *locally* here (the deeplink initiation channel), so
     * the gateway must suppress the redundant websocket push for this transaction (ARISE-4420).
     */
    @JvmStatic
    fun buildLaunchIntent(posTransactionId: String, notificationPayloadJson: String): Intent =
        Intent(ACTION_START_PAYMENT).apply {
            setPackage(TERMINAL_PACKAGE)
            data = Uri.Builder().scheme(SCHEME).authority("payment").build()
            putExtra(EXTRA_POS_TRANSACTION_ID, posTransactionId)
            putExtra(EXTRA_NOTIFICATION_PAYLOAD, notificationPayloadJson)
        }
}
