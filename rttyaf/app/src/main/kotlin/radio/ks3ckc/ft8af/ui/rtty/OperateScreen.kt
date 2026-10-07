package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.SignalSoft
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextDim
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun OperateScreen(state: RttyAppState) {
    val engine = state.engine
    val cfg = engine.config
    // Real transmit state from the engine's TX backend (true while an over is on
    // the air), so the TX pane, banner and TX/STOP button reflect the actual rig
    // keying rather than a UI-only toggle.
    val txOn by engine.transmittingLive.observeAsState(false)

    var utc by remember { mutableStateOf(utcNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            utc = utcNow()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().background(BgApp)) {
        // ---- Header ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("RTTY", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("UTC $utc", color = TextMuted, fontSize = 13.sp, fontFamily = GeistMonoFamily)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    text = if (engine.listening) "RX" else "IDLE",
                    dot = if (engine.listening) StatusConfirmed else TextFaint,
                    bg = BgSurface, fg = TextMuted, border = true,
                )
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(SignalSoft)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "${trimNum(cfg.baudRate)} / ${cfg.shiftHz}",
                        color = Signal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }
        }

        // ---- Waterfall card ----
        Column(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
                .background(BgSurface).border(1.dp, Border, RoundedCornerShape(12.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MARK ${cfg.markToneHz.toInt()}", color = Signal, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                    Text("SPACE ${cfg.spaceToneHz.toInt()}", color = Accent, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniToggle("AFC", engine.afc) { engine.afc = !engine.afc }
                    MiniToggle("NET", engine.net) { engine.net = !engine.net }
                }
            }
            Waterfall(engine, Modifier.fillMaxWidth().height(108.dp))
        }

        // ---- RX + TX panes ----
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RxPane(engine, Modifier.weight(1f).fillMaxWidth())
            // TX pane
            Row(
                Modifier.height(46.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (txOn) SignalSoft else BgSurface)
                    .border(1.dp, if (txOn) Signal else Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (txOn) "TX" else "TX BUF", color = if (txOn) Signal else TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    state.txPreview.ifEmpty { "—" },
                    color = if (state.txPreview.isEmpty()) TextDim else Color(0xFFFFD7A0),
                    fontSize = 13.sp, fontFamily = GeistMonoFamily, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- Entry row ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isDupe = state.log.any { it.call.equals(state.call, ignoreCase = true) && state.call.isNotBlank() }
            EntryField("CALL", state.call, Signal, Modifier.weight(1.4f), isDupe) { state.call = it.uppercase() }
            EntryField("RST S", state.rstSent, Accent, Modifier.weight(1f)) { state.rstSent = it }
            // The worked station's exchange (their grid/zone/serial), stored with
            // the QSO. Our own exchange ({EXCH} in macros) is state.myExchange,
            // edited on the Contest screen — typing here must not change it.
            EntryField("EXCH R", state.rcvdExchange, Accent, Modifier.weight(1f)) { state.rcvdExchange = it.uppercase() }
            Box(
                Modifier.width(58.dp).height(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (state.call.isNotBlank()) Signal else BgSurface2)
                    .clickable(enabled = state.call.isNotBlank()) { logQso(state) },
                contentAlignment = Alignment.Center,
            ) {
                Text("LOG", color = if (state.call.isNotBlank()) BgApp else TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Macro grid (4 x 2) ----
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.macros.chunked(4).forEach { rowMacros ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowMacros.forEach { m ->
                        MacroButton(m, Modifier.weight(1f)) { state.txPreview = state.expand(m.text) }
                    }
                }
            }
        }

        // ---- Band bar + TX toggle ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(BgSurface).border(1.dp, Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("BAND", color = TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(state.bandLabel, color = TextPrimary, fontSize = 14.sp, fontFamily = GeistMonoFamily)
            }
            // Dev self-test button so decode is visible without a rig
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(BgSurface2)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .clickable { engine.injectLoopbackTest() }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("TEST", color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.width(84.dp).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (txOn) StatusBad else Accent)
                    .clickable {
                        // TX sends the staged buffer (key → modulate → unkey);
                        // STOP aborts an over in progress.
                        if (txOn) engine.stopTx() else engine.transmit(state.txPreview)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (txOn) "STOP" else "TX", color = BgApp, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * RX text pane. Isolated so decoded-text updates recompose only this subtree,
 * not the whole Operate screen, and so the auto-scroll follows layout.
 */
@Composable
private fun RxPane(engine: radio.ks3ckc.ft8af.rtty.RttyEngine, modifier: Modifier) {
    val rxScroll = rememberScrollState()
    // Follow the newest line by observing maxValue *after* the taller content is
    // laid out, rather than reading it the instant rxText changes (which is
    // pre-layout, so it would scroll to the previous, shorter extent).
    LaunchedEffect(rxScroll) {
        snapshotFlow { rxScroll.maxValue }.collectLatest { rxScroll.scrollTo(it) }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(BgSurface).border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rxScroll)
    ) {
        val rx = engine.rxText
        val body = rx.ifEmpty { "Listening for RTTY…  tap TEST to self-decode a loopback signal." }
        Text(
            body + "▮",
            color = if (rx.isEmpty()) TextDim else TextPrimary,
            fontSize = 13.sp, fontFamily = GeistMonoFamily, lineHeight = 20.sp,
        )
    }
}

@Composable
private fun Waterfall(engine: radio.ks3ckc.ft8af.rtty.RttyEngine, modifier: Modifier) {
    // Snapshot-backed so appending a spectrum column invalidates the Canvas draw;
    // a plain ArrayDeque would only repaint on incidental recomposition.
    val history = remember { mutableStateListOf<FloatArray>() }
    LaunchedEffect(engine.spectrum) {
        val col = engine.spectrum
        if (col.isNotEmpty()) {
            history.add(col)
            while (history.size > MAX_ROWS) history.removeAt(0)
        }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val rows = history.toList()
            if (rows.isNotEmpty()) {
                val rowH = size.height / MAX_ROWS
                rows.forEachIndexed { idx, col ->
                    val y = size.height - (rows.size - idx) * rowH
                    val bins = col.size
                    val cellW = size.width / bins
                    for (b in 0 until bins) {
                        val m = col[b]
                        if (m > 0.06f) {
                            val c = if (m < 0.5f) lerp(BgSurface, Signal, m * 2f)
                            else lerp(Signal, Color.White, (m - 0.5f) * 2f)
                            drawRect(c.copy(alpha = 0.9f), Offset(b * cellW, y), androidx.compose.ui.geometry.Size(cellW + 0.5f, rowH + 0.5f))
                        }
                    }
                }
            }
            // mark/space tuning cursors
            drawRect(Signal.copy(alpha = 0.85f), Offset(size.width * engine.markFraction, 0f), androidx.compose.ui.geometry.Size(1.5f, size.height))
            drawRect(Accent.copy(alpha = 0.85f), Offset(size.width * engine.spaceFraction, 0f), androidx.compose.ui.geometry.Size(1.5f, size.height))
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("500", "1000", "1500", "2000", "2500", "3000").forEach {
                Text(it, color = TextFaint, fontSize = 9.sp, fontFamily = GeistMonoFamily)
            }
        }
    }
}

@Composable
private fun Chip(text: String, dot: Color, bg: Color, fg: Color, border: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .then(if (border) Modifier.border(1.dp, Border, RoundedCornerShape(999.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(7.dp).height(7.dp).clip(RoundedCornerShape(999.dp)).background(dot))
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MiniToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(6.dp))
            .background(if (on) SignalSoft else BgSurface2)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Signal else TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EntryField(label: String, value: String, valueColor: Color, modifier: Modifier, dupe: Boolean = false, onChange: (String) -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(BgSurface2)
            .border(1.dp, if (dupe) StatusBad else Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = TextFaint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                if (dupe) Text("DUPE", color = StatusBad, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily),
                cursorBrush = SolidColor(valueColor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MacroButton(m: RttyMacro, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(BgSurface2)
            .border(1.dp, Border, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(m.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(m.key, color = TextFaint, fontSize = 9.sp, fontFamily = GeistMonoFamily)
    }
}

private fun logQso(state: RttyAppState) {
    if (state.call.isBlank()) return
    state.log.add(
        0,
        RttyLogEntry(
            call = state.call.uppercase(), band = state.bandShort, timeUtc = utcNow(),
            rstSent = state.rstSent, rstRcvd = state.rstRcvd,
            exchSent = state.myExchange, exchRcvd = state.rcvdExchange, serial = state.serial,
            synced = false,
        ),
    )
    state.serial += 1
    state.call = ""
    state.rcvdExchange = ""
}

private fun utcNow(): String {
    val f = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return f.format(Date())
}

private fun trimNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

private const val MAX_ROWS = 44
