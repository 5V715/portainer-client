package dev.silas.portainer.app.detail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextStickTest {
    @Test
    fun userScrollingUpUnsticks() = assertFalse(nextStick(50, 50, 99L, 99L, atBottom = false, stick = true))

    @Test
    fun growthWhileStuckStaysStuckEvenThoughViewportIsNoLongerAtBottom() =
        assertTrue(nextStick(50, 60, 99L, 109L, atBottom = false, stick = true))

    @Test
    fun growthWhileUnstuckStaysUnstuckEvenIfAtBottom() =
        assertFalse(nextStick(50, 60, 99L, 109L, atBottom = true, stick = false))

    @Test
    fun userReturningToBottomSticksAgain() = assertTrue(nextStick(50, 50, 99L, 99L, atBottom = true, stick = false))

    @Test
    fun capacityTrimKeepsCountButNewLastKeyWhileStuckStaysStuck() =
        assertTrue(nextStick(5000, 5000, 9999L, 10010L, atBottom = false, stick = true))

    @Test
    fun sameCountFilterChangeWhileUnstuckStaysUnstuck() =
        assertFalse(nextStick(10, 10, 40L, 77L, atBottom = true, stick = false))
}
