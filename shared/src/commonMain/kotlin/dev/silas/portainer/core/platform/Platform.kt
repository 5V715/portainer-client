package dev.silas.portainer.core.platform

import io.ktor.client.engine.HttpClientEngine

/** Platform-specific Ktor engine. */
expect fun httpEngine(): HttpClientEngine

/** Whether the engine can deliver response bodies incrementally (needed for live log following). */
expect val platformSupportsStreaming: Boolean
