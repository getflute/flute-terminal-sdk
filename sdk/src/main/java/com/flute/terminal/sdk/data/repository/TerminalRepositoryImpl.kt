package com.flute.terminal.sdk.data.repository

import com.flute.terminal.sdk.data.auth.TokenProvider
import com.flute.terminal.sdk.data.mapper.Mappers
import com.flute.terminal.sdk.data.remote.FluteApi
import com.flute.terminal.sdk.data.remote.apiCall
import com.flute.terminal.sdk.domain.repository.TerminalRepository
import com.flute.terminal.sdk.model.TerminalInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class TerminalRepositoryImpl(
    private val api: FluteApi,
    private val tokenProvider: TokenProvider,
    private val io: CoroutineDispatcher,
) : TerminalRepository {
    override suspend fun list(): List<TerminalInfo> = withContext(io) {
        apiCall { api.listTerminals(tokenProvider.bearer()) }.items.map(Mappers::toInfo)
    }

    override suspend fun findBySerial(serialNumber: String): TerminalInfo? = withContext(io) {
        apiCall { api.listTerminals(tokenProvider.bearer(), serialNumber) }.items.map(Mappers::toInfo).firstOrNull()
    }
}
