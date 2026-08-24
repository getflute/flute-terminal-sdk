package com.flute.terminal.sdk.domain.repository

import com.flute.terminal.sdk.model.TerminalInfo

/** Terminal discovery resource. Pure domain contract — no Android/Retrofit leaks. */
internal interface TerminalRepository {
    suspend fun list(): List<TerminalInfo>

    /** Resolves this device's terminal by its serial number (`GET /v2/terminals?serialNumber=`). */
    suspend fun findBySerial(serialNumber: String): TerminalInfo?
}
