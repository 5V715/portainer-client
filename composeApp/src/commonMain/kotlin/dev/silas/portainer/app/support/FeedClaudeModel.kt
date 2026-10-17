package dev.silas.portainer.app.support

import kotlin.math.min
import kotlin.random.Random

internal const val TALK_MS = 2600L
internal const val PX = 3f // canvas units per sprite pixel
internal const val SPEED = 46f // units per second
internal const val GAP = 26f // between coins
internal const val TREAT_CHANCE = 0.18f
internal const val RESPAWN_MS = 1200L
private const val BODY_W = 12 * PX

internal class Coin(val x: Float, val treat: Treat?) {
    var eaten = false
    var pop = 0f
}

internal class Talk(val line: Int, val untilMs: Long)

/** Pure simulation of the donation-box mascot; ported from donate.js. Rendering lives in FeedClaude.kt. */
internal class FeedClaudeModel(width: Float, private val random: Random = Random.Default) {
    var width: Float = width
        private set
    var x: Float = -40f
        private set
    var coins: List<Coin> = emptyList()
        private set
    var talk: Talk? = null
        private set

    /** The line currently being said, or null. */
    val speech: Line? get() = talk?.let { LINES[it.line] }

    private var nextTalkMs = -1L
    private var respawnAtMs = 0L
    private var lastLine = -1

    init {
        if (width > 0f) fillCoins()
    }

    fun resize(newWidth: Float) {
        width = newWidth
        if (coins.isEmpty() && newWidth > 0f) fillCoins()
    }

    /** Reduced motion: a static mascot, centred. */
    fun centre() {
        x = width / 2 - BODY_W / 2
    }

    private fun fillCoins() {
        val list = ArrayList<Coin>()
        var cx = 30f
        while (cx < width - 10f) {
            val treat = if (random.nextFloat() < TREAT_CHANCE) Treat.entries[random.nextInt(Treat.entries.size)] else null
            list += Coin(cx, treat)
            cx += GAP
        }
        coins = list
    }

    private fun talkGap(): Long = 6000L + (random.nextFloat() * 7000f).toLong()

    fun step(dtSeconds: Float, nowMs: Long) {
        val dt = min(0.1f, dtSeconds)
        if (nextTalkMs < 0) nextTalkMs = nowMs + talkGap()
        val current = talk
        if (current != null && nowMs > current.untilMs) {
            talk = null
            nextTalkMs = nowMs + talkGap()
        }
        if (talk == null && nowMs > nextTalkMs && x > 4f && x < width - BODY_W - 4f) {
            var line: Int
            do line = random.nextInt(LINES.size) while (line == lastLine)
            lastLine = line
            talk = Talk(line, nowMs + TALK_MS)
        }
        if (talk == null) x += SPEED * dt
        if (x > width + 10f) x = -BODY_W - 10f // through the tunnel, back to the left
        val mouthX = x + 11 * PX
        for (c in coins) {
            if (!c.eaten && mouthX >= c.x - 2f && x < c.x) {
                c.eaten = true
                c.pop = 1f
            }
            if (c.pop > 0f) c.pop = maxOf(0f, c.pop - dt * if (c.treat == null) 1.6f else 0.8f)
        }
        if (coins.isNotEmpty() && coins.all { it.eaten }) {
            if (respawnAtMs == 0L) respawnAtMs = nowMs + RESPAWN_MS
            else if (nowMs > respawnAtMs) {
                respawnAtMs = 0L
                fillCoins()
            }
        }
    }
}
