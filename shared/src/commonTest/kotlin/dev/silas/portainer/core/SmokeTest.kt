package dev.silas.portainer.core

import dev.silas.portainer.core.platform.httpEngine
import kotlin.test.Test
import kotlin.test.assertNotNull

class SmokeTest {
    @Test
    fun engineIsAvailable() {
        assertNotNull(httpEngine())
    }
}
