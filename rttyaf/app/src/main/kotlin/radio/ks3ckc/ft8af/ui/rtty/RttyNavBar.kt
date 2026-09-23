package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.SignalSoft
import radio.ks3ckc.ft8af.theme.TextFaint

@Composable
fun RttyNavBar(
    active: RttyTab,
    onSelect: (RttyTab) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BgSurface)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in RttyTab.entries) {
                NavItem(
                    tab = t,
                    active = t == active,
                    onClick = { onSelect(t) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(Modifier.height(bottomInset).fillMaxWidth().background(BgSurface))
    }
}

@Composable
private fun NavItem(tab: RttyTab, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val color by animateColorAsState(if (active) Signal else TextFaint, label = "nav-color")
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    Column(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (active) SignalSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            NavIcon(tab, color)
        }
        Text(
            text = tab.label(),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

private fun RttyTab.label(): String = when (this) {
    RttyTab.OPERATE -> "Operate"
    RttyTab.CONTEST -> "Contest"
    RttyTab.MACROS -> "Macros"
    RttyTab.LOG -> "Log"
    RttyTab.SETTINGS -> "Settings"
}

@Composable
private fun NavIcon(tab: RttyTab, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        when (tab) {
            RttyTab.OPERATE -> {
                // spectrum bars
                val n = 4
                val gap = w / (n * 2f)
                val heights = floatArrayOf(0.45f, 0.85f, 0.6f, 0.95f)
                for (i in 0 until n) {
                    val x = gap + i * 2 * gap
                    val bh = h * heights[i]
                    drawLine(color, Offset(x, h - bh), Offset(x, h), strokeWidth = sw, cap = StrokeCap.Round)
                }
            }
            RttyTab.CONTEST -> {
                // trophy: cup + stem + base
                val cupW = w * 0.5f
                val cx = w / 2f
                drawRoundRectStroke(color, cx - cupW / 2f, h * 0.12f, cupW, h * 0.42f, sw)
                drawLine(color, Offset(cx, h * 0.54f), Offset(cx, h * 0.72f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(cx - w * 0.18f, h * 0.82f), Offset(cx + w * 0.18f, h * 0.82f), sw, cap = StrokeCap.Round)
            }
            RttyTab.MACROS -> {
                // command prompt: ">" and "_"
                drawLine(color, Offset(w * 0.22f, h * 0.32f), Offset(w * 0.45f, h * 0.5f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.45f, h * 0.5f), Offset(w * 0.22f, h * 0.68f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.55f, h * 0.72f), Offset(w * 0.82f, h * 0.72f), sw, cap = StrokeCap.Round)
            }
            RttyTab.LOG -> {
                // book: two pages
                val top = h * 0.2f; val bot = h * 0.8f; val cx = w / 2f
                drawLine(color, Offset(cx, top), Offset(cx, bot), sw, cap = StrokeCap.Round)
                drawArcSpine(color, w * 0.16f, top, cx, bot, sw)
                drawArcSpine(color, cx, top, w * 0.84f, bot, sw)
            }
            RttyTab.SETTINGS -> {
                // gear: circle + 8 teeth
                val cx = w / 2f; val cy = h / 2f; val r = w * 0.24f
                drawCircle(color, r, Offset(cx, cy), style = stroke)
                for (k in 0 until 8) {
                    rotate(k * 45f, Offset(cx, cy)) {
                        drawLine(color, Offset(cx, cy - r - sw), Offset(cx, cy - r - w * 0.14f), sw, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRoundRectStroke(color: Color, x: Float, y: Float, w: Float, h: Float, sw: Float) {
    drawLine(color, Offset(x, y), Offset(x + w, y), sw, cap = StrokeCap.Round)
    drawLine(color, Offset(x, y), Offset(x, y + h), sw, cap = StrokeCap.Round)
    drawLine(color, Offset(x + w, y), Offset(x + w, y + h), sw, cap = StrokeCap.Round)
    drawLine(color, Offset(x + w * 0.15f, y + h), Offset(x + w * 0.85f, y + h), sw, cap = StrokeCap.Round)
}

private fun DrawScope.drawArcSpine(color: Color, x0: Float, top: Float, x1: Float, bot: Float, sw: Float) {
    drawLine(color, Offset(x0, top), Offset(x1, top), sw, cap = StrokeCap.Round)
    drawLine(color, Offset(x0, top), Offset(x0, bot), sw, cap = StrokeCap.Round)
    drawLine(color, Offset(x0, bot), Offset(x1, bot), sw, cap = StrokeCap.Round)
}
