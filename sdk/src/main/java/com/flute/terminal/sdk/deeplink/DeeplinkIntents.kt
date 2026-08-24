package com.flute.terminal.sdk.deeplink

import android.content.Intent
import com.flute.terminal.deeplink.FluteDeeplinkContract
import com.flute.terminal.sdk.model.PosTransactionRef

/** Maps the created POS transaction onto the shared (primitive-typed) deeplink contract. */
internal object DeeplinkIntents {

    /**
     * Builds the launch Intent carrying the POS-transaction notification payload verbatim, so the
     * terminal app parses it with its existing notification model and reuses its transaction flow.
     */
    fun buildLaunchIntent(ref: PosTransactionRef): Intent =
        FluteDeeplinkContract.buildLaunchIntent(
            posTransactionId = ref.posTransactionId,
            notificationPayloadJson = ref.notificationPayloadJson,
        )
}
