package dev.silas.portainer.core.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun httpEngine(): HttpClientEngine = OkHttp.create()

actual val platformSupportsStreaming: Boolean = true
