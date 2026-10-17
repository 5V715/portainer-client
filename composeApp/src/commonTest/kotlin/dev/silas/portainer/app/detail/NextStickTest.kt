package dev.silas.portainer.app.detail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextStickTest {
    @Test
    fun userScrollingUpUnsticks() = assertFalse(nextStick(prevCount = 50, count = 50, atBottom = false, stick = true))

    @Test
    fun growthWhileStuckStaysStuckEvenThoughViewportIsNoLongerAtBottom() =
        assertTrue(nextStick(prevCount = 50, count = 60, atBottom = false, stick = true))

    @Test
    fun growthWhileUnstuckStaysUnstuckEvenIfAtBottom() =
        assertFalse(nextStick(prevCount = 50, count = 60, atBottom = true, stick = false))

    @Test
    fun userReturningToBottomSticksAgain() = assertTrue(nextStick(prevCount = 50, count = 50, atBottom = true, stick = false))
}
