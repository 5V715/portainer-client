package dev.silas.portainer.app.support

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedClaudeModelTest {
    private fun FeedClaudeModel.run(fromMs: Long, toMs: Long, stepMs: Long = 100, until: () -> Boolean = { false }): Long {
        var t = fromMs
        while (t < toMs && !until()) {
            t += stepMs
            step(stepMs / 1000f, t)
        }
        return t
    }

    @Test
    fun advancesBySpeedTimesDtWhenNotTalking() {
        val m = FeedClaudeModel(300f, Random(1))
        val x0 = m.x
        m.step(0.1f, 100)
        assertEquals(x0 + SPEED * 0.1f, m.x, 0.001f)
    }

    @Test
    fun eatsCoinWhenMouthPassesIt() {
        val m = FeedClaudeModel(300f, Random(1))
        val first = m.coins.first()
        assertTrue(!first.eaten)
        m.run(0, 3000) { first.eaten }
        assertTrue(first.eaten)
        assertEquals(1f, first.pop, 0.2f)
        assertTrue(first.pop > 0f)
    }

    @Test
    fun wrapsToTheLeftAfterTunnel() {
        val m = FeedClaudeModel(100f, Random(1))
        var previous = m.x
        var wrapped = false
        var t = 0L
        while (t < 5500 && !wrapped) {
            t += 100
            m.step(0.1f, t)
            if (m.x < previous) wrapped = true
            previous = m.x
        }
        assertTrue(wrapped)
        assertEquals(-12 * PX - 10f, m.x, 0.001f + SPEED * 0.1f)
    }

    @Test
    fun respawnsCoinsOnceAfterTheyAreAllEaten() {
        val m = FeedClaudeModel(100f, Random(1))
        var t = m.run(0, 6000) { m.coins.all { it.eaten } }
        assertTrue(m.coins.all { it.eaten })
        val eatenAt = t
        t = m.run(t, eatenAt + 1000)
        assertTrue(m.coins.all { it.eaten }, "still waiting before 1.2 s")
        m.run(t, eatenAt + 1500)
        assertTrue(m.coins.any { !it.eaten }, "fresh coins after 1.2 s")
    }

    @Test
    fun neverTalksWithinSixSecondsOfStart() {
        val m = FeedClaudeModel(5000f, Random(7))
        var t = 0L
        while (t < 5900) {
            t += 100
            m.step(0.1f, t)
            assertNull(m.speech, "talked at $t")
        }
    }

    @Test
    fun talkingStopsMovementAndEndsAfterTalkMs() {
        val m = FeedClaudeModel(5000f, Random(7))
        var t = m.run(0, 20_000) { m.speech != null }
        assertNotNull(m.speech)
        assertTrue(t >= 6000)
        val started = t
        val x = m.x
        t = m.run(t, started + 2000)
        assertEquals(x, m.x)
        assertNotNull(m.speech)
        m.run(t, started + TALK_MS + 300)
        assertNull(m.speech)
        assertTrue(m.x > x)
    }
}
