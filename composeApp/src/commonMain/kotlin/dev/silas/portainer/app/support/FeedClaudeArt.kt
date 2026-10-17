package dev.silas.portainer.app.support

import androidx.compose.ui.graphics.Color

// Ported from games-with-friends public/js/donate.js (English only).

internal fun palColor(c: Char): Color? = when (c) {
    'o' -> Color(0xFFD97757)
    'd' -> Color(0xFFA9533A)
    'k' -> Color(0xFF1A0F2E)
    'h' -> Color(0xFFF0A07F)
    else -> null
}

/** 12x10, facing right. Two walking frames; the mouth opens while chomping. */
internal val BODY = listOf(
    "............",
    ".hhhhhhhhhh.",
    ".oooooooooo.",
    ".ooookoookoo",
    "oooookoookoo",
    "oooooooooooo",
    ".oooooooooo.",
    ".oooooooooo.",
)
internal val LEGS = listOf(
    listOf(".o.o....o.o.", ".d.d....d.d."),
    listOf("..o.o..o.o..", "..d.d..d.d.."),
)
internal val MOUTH = listOf(9 to 5, 10 to 5, 11 to 5, 10 to 6, 11 to 6)

/** k glass/outline, w white, y/a beer, o bun/crust, s seeds, g green, b patty/brown, c cheese, r red, p pink. */
internal fun treatColor(c: Char): Color? = when (c) {
    'w' -> Color(0xFFFFFFFF); 'y' -> Color(0xFFF2B632); 'a' -> Color(0xFFFFE08A); 'k' -> Color(0xFFCFD6E0)
    'o' -> Color(0xFFE0A050); 's' -> Color(0xFFFFF3C4); 'g' -> Color(0xFF5BBF4A); 'b' -> Color(0xFF6B3A1F)
    'c' -> Color(0xFFFFCC33); 'r' -> Color(0xFFD2422F); 'p' -> Color(0xFFFF8AC6); 'd' -> Color(0xFF2F7D3A)
    'm' -> Color(0xFF1A0F2E); 't' -> Color(0xFFFFD23A)
    else -> null
}

/** Both walking frames, body plus legs, built once. */
internal val FRAMES: List<List<String>> = LEGS.map { BODY + it }

internal enum class Treat(val say: String, val map: List<String>) {
    Beer(
        "CHEERS!",
        listOf(
            ".wwwwww..", "wwwwwwww.", "yayyyyy..", "yayyyyykk", "yayyyyy.k", "yayyyyy.k", "yayyyyykk", "yyyyyyy..",
        ),
    ),
    Burger(
        "YUM!",
        listOf(
            "..ooooo..", ".oosoaso.", "ooooooooo", "ggggggggg", "ccccccccc", "bbbbbbbbb", "ooooooooo", ".ooooooo.",
        ),
    ),
    Pizza(
        "YUM!",
        listOf(
            "ooooooooo", ".crcccrc.", ".ccccccc.", "..ccrcc..", "..ccccc..", "...crc...", "....c....",
        ),
    ),
    Donut(
        "YUM!",
        listOf(
            "..pppp..", ".pwppwp.", "pppoopwp", "ppo..opp", "pwo..opp", "pppoopwp", ".pwppwp.", "..pppp..",
        ),
    ),
    Gpu(
        "+1 GPU",
        listOf(
            "dddddddddd", "dmmmdmmmdd", "dmwmdmwmdd", "dmmmdmmmdd", "dddddddddd", "t.t.t.t...",
        ),
    ),
}

/** What it says when it stops; [dots] animates trailing dots. */
internal class Line(val text: String, val dots: Boolean = false) {
    /** Every string it can show: one entry, or three with 1..3 trailing dots. */
    val variants: List<String> = if (dots) (1..3).map { text + ".".repeat(it) } else listOf(text)
}

internal val LINES = listOf(
    Line("I WANT MORE!"),
    Line("THANKS!"),
    Line("MORE TOKENS!"),
    Line("THINKING", dots = true),
    Line("*BURP*"),
    Line("STILL HUNGRY"),
    Line("OM NOM NOM"),
    Line("YOU'RE ABSOLUTELY RIGHT!"),
    Line("CONTEXT FULL..."),
    Line("JUST ONE MORE!"),
)
