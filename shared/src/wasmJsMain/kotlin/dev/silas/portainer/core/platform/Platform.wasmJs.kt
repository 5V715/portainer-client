package dev.silas.portainer.core.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

actual fun httpEngine(): HttpClientEngine = Js.create()

actual val platformSupportsStreaming: Boolean = false
