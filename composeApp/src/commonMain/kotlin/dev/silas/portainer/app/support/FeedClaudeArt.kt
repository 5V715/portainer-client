package dev.silas.portainer.app.support

import androidx.compose.ui.graphics.Color

// Ported from games-with-friends public/js/donate.js (English only).

internal val PAL = mapOf(
    'o' to Color(0xFFD97757),
    'd' to Color(0xFFA9533A),
    'k' to Color(0xFF1A0F2E),
    'h' to Color(0xFFF0A07F),
)

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
internal val TREAT_PAL = mapOf(
    'w' to Color(0xFFFFFFFF), 'y' to Color(0xFFF2B632), 'a' to Color(0xFFFFE08A), 'k' to Color(0xFFCFD6E0),
    'o' to Color(0xFFE0A050), 's' to Color(0xFFFFF3C4), 'g' to Color(0xFF5BBF4A), 'b' to Color(0xFF6B3A1F),
    'c' to Color(0xFFFFCC33), 'r' to Color(0xFFD2422F), 'p' to Color(0xFFFF8AC6), 'd' to Color(0xFF2F7D3A),
    'm' to Color(0xFF1A0F2E), 't' to Color(0xFFFFD23A),
)

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
internal class Line(val text: String, val dots: Boolean = false)

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
