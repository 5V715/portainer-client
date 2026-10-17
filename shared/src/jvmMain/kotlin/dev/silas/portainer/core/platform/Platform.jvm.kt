package dev.silas.portainer.core.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

actual fun httpEngine(): HttpClientEngine = CIO.create()

actual val platformSupportsStreaming: Boolean = true
