package dev.silas.portainer.app.support

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.jetbrains.compose.resources.Font
import portainer_client.composeapp.generated.resources.Res
import portainer_client.composeapp.generated.resources.press_start_2p
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val Navy = Color(0xFF1A0F2E)
private val Magenta = Color(0xFFFF4FD8)
private val Gold = Color(0xFFFFE14D)
private val InkDim = Color(0xFFA99CD6)

private const val PAYPAL = "https://www.paypal.com/donate/?hosted_button_id=M6D3G2TRTEQXG"
private const val KOFI = "https://ko-fi.com/silasschwarz"
private const val SPONSOR = "https://github.com/sponsors/5V715"

/** The "INSERT COIN" donation box: a mascot eating coins above a short text and three tip links. */
@Composable
fun FeedClaudeBox(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val pixelFont = FontFamily(Font(Res.font.press_start_2p))
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(8.dp, shape, ambientColor = Magenta, spotColor = Magenta)
            .background(Color(0xFF0D0724), shape)
            .border(2.dp, Magenta.copy(alpha = 0.4f), shape)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MascotTrack(pixelFont, Modifier.fillMaxWidth().height(46.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "INSERT COIN",
                style = TextStyle(
                    fontFamily = pixelFont,
                    fontSize = 11.sp,
                    lineHeight = 15.4.sp,
                    color = Gold,
                    shadow = Shadow(Gold.copy(alpha = 0.6f), blurRadius = 8f),
                ),
            )
            Text(
                "🍺 Feed my Claude – a tip keeps the tokens flowing. Entirely optional.",
                color = InkDim,
                fontSize = 13.sp,
                lineHeight = 17.5.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PayButton("PayPal", Color(0xFF00457C), Modifier.weight(1f)) { uri.openUri(PAYPAL) }
            PayButton("Ko-fi", Color(0xFFFF5E5B), Modifier.weight(1f)) { uri.openUri(KOFI) }
            PayButton("Sponsor", Color(0xFFEA4AAA), Modifier.weight(1f)) { uri.openUri(SPONSOR) }
        }
    }
}

@Composable
private fun PayButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .shadow(5.dp, shape, ambientColor = color, spotColor = color)
            .background(color, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MascotTrack(pixelFont: FontFamily, modifier: Modifier) {
    val model = remember { FeedClaudeModel(0f) }
    var frame by remember { mutableLongStateOf(0L) }
    val measurer = rememberTextMeasurer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // The loop ends when this leaves composition and is suspended while the app is not visible.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { nanos ->
                if (last < 0) last = nanos
                val nowMs = nanos / 1_000_000
                model.step((nanos - last) / 1e9f, nowMs)
                last = nanos
                frame = nowMs
            }
        }
    }

    Canvas(modifier) {
        val density = this.density
        val w = size.width / density
        val h = size.height / density
        if (model.width != w) model.resize(w)
        scale(density, density, Offset.Zero) {
            drawTrack(model, frame, w, h, measurer, pixelFont)
        }
    }
}

private fun DrawScope.px(color: Color, x: Float, y: Float, w: Float, h: Float) =
    drawRect(color, Offset(x, y), Size(w, h))

private fun DrawScope.sprite(sx: Float, sy: Float, frame: Int, chomp: Boolean) {
    val rows = BODY + LEGS[frame]
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { xx, c ->
            val color = PAL[c] ?: return@forEachIndexed
            if (chomp && MOUTH.any { (mx, my) -> mx == xx && my == y }) return@forEachIndexed
            px(color, sx + xx * PX, sy + y * PX, PX, PX)
        }
    }
}

private fun DrawScope.coin(cx: Float, cy: Float, t: Long) {
    // pixel coin that slowly "spins" (gets narrower and wider)
    val wv = max(2, (3 + 2 * cos(t / 300.0 + cx)).roundToInt()).toFloat()
    px(Color(0xFFC98A00), cx - wv, cy - 5, wv * 2, 10f)
    px(Color(0xFFFFD23A), cx - wv + 1, cy - 4, wv * 2 - 2, 8f)
    px(Color(0xFFFFF6B0), cx - wv + 1, cy - 4, 2f, 2f)
}

private fun DrawScope.treat(kind: Treat, cx: Float, cy: Float, t: Long) {
    val map = kind.map
    val tw = map[0].length * 2
    val th = map.size * 2
    val bob = (sin(t / 250.0 + cx) * 1.5).roundToInt()
    val ox = (cx - tw / 2f).roundToInt().toFloat()
    val oy = (cy - th / 2f).roundToInt().toFloat() + bob
    map.forEachIndexed { y, row ->
        row.forEachIndexed { x, c ->
            val color = TREAT_PAL[c] ?: return@forEachIndexed
            px(color, ox + x * 2, oy + y * 2, 2f, 2f)
        }
    }
}


private fun DrawScope.bubble(measurer: TextMeasurer, font: FontFamily, text: String, mx: Float, my: Float, w: Float) {
    val layout = measurer.measure(text, TextStyle(fontFamily = font, fontSize = 7.sp), density = Density(1f))
    val tw = layout.size.width.toFloat()
    val bw = tw + 10
    val bh = 15f
    val body = 12 * PX
    val right = mx + body + 6 + bw <= w - 2
    val bx = if (right) mx + body + 6 else max(2f, mx - bw - 6)
    val by = max(1f, my - 2)
    px(Navy, bx, by, bw, bh)
    px(Magenta, bx, by, bw, 1f)
    px(Magenta, bx, by + bh - 1, bw, 1f)
    px(Magenta, bx, by, 1f, bh)
    px(Magenta, bx + bw - 1, by, 1f, bh)
    // little tail pointing at the mascot
    val tx = if (right) bx - 1 else bx + bw
    for (i in 0 until 4) px(Magenta, if (right) tx - i else tx + i, by + 4 + i, 1f, 7f - i * 2)
    drawText(layout, Color.White, Offset(bx + 5, by + 11 - layout.firstBaseline))
}

private fun DrawScope.drawTrack(model: FeedClaudeModel, t: Long, w: Float, h: Float, measurer: TextMeasurer, font: FontFamily) {
    val ground = h - 4
    // dotted floor line like a maze lane
    var i = 0f
    while (i < w) {
        px(Color(0xFF38F2FF).copy(alpha = 0.25f), i, ground, 4f, 2f)
        i += 8
    }
    val cy = ground - 15
    for (c in model.coins) {
        val kind = c.treat
        if (!c.eaten) {
            if (kind == null) coin(c.x, cy, t) else treat(kind, c.x, cy - 1, t)
        } else if (c.pop > 0f) {
            val text = if (kind == null) "+1" else kind.say
            val color = if (kind == null) Color(0xFFFFE14D) else Color(0xFFFF8AC6)
            val layout = measurer.measure(text, TextStyle(fontFamily = font, fontSize = 8.sp), density = Density(1f))
            val tw = layout.size.width.toFloat()
            val x = min(w - tw - 2, max(2f, c.x - tw / 2))
            drawText(layout, color.copy(alpha = c.pop), Offset(x, cy - 12 - (1 - c.pop) * 10 - layout.firstBaseline))
        }
    }
    val talking = model.speech
    val frame = if (talking != null) 0 else (floor(t / 140.0).toInt()) % 2
    val chomp = if (talking != null) floor(t / 180.0).toInt() % 2 == 0 else floor(t / 110.0).toInt() % 2 == 0
    val my = ground - 10 * PX - 1
    val mx = model.x.roundToInt().toFloat()
    sprite(mx, my, frame, chomp)
    if (talking != null) {
        val text = talking.text + if (talking.dots) ".".repeat(1 + (floor(t / 300.0).toInt() % 3)) else ""
        bubble(measurer, font, text, mx, my, w)
    }
}
