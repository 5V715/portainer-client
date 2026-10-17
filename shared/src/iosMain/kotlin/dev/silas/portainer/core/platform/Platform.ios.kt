package dev.silas.portainer.core.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun httpEngine(): HttpClientEngine = Darwin.create()

actual val platformSupportsStreaming: Boolean = true
