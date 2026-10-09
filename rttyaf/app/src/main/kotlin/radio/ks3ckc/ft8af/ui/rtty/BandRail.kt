package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import radio.ks3ckc.ft8af.rtty.spots.RbnClient
import radio.ks3ckc.ft8af.rtty.spots.RttySpot
import radio.ks3ckc.ft8af.rtty.spots.SpotService
import radio.ks3ckc.ft8af.rtty.spots.SpotSource
import radio.ks3ckc.ft8af.rtty.spots.SpotTuner
import radio.ks3ckc.ft8af.rtty.spots.formatKHz
import radio.ks3ckc.ft8af.rtty.spots.goIsCopying
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.AccentSoft
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.SignalSoft
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary

/** Green used for RBN pips/tags (same hue as the status dot so "live" reads consistently). */
internal val RbnGreen: Color get() = StatusConfirmed

/**
 * The strip above the waterfall: the current band's RTTY segment with one pip
 * per spotted station, the rig's passband as a highlighted window, and
 * callsign labels on the strongest few. Tapping a pip goes to that station.
 * While a go is in flight the rail is replaced by [GoBanner].
 */
@Composable
fun BandRail(service: SpotService, rxText: String, onOpenSheet: () -> Unit, modifier: Modifier = Modifier) {
    val go = service.goTarget
    if (go != null) {
        GoBanner(service, go, rxText, modifier)
        return
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val segment = service.segment
    val pips = railPips(service.spots, segment, now)
    val labels = labelledPips(pips)
    val window = passbandWindow(segment, service.dialHz)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = TextPrimary, fontSize = 9.sp, fontFamily = GeistMonoFamily)
    val axisStyle = TextStyle(color = TextFaint, fontSize = 9.sp, fontFamily = GeistMonoFamily)

    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(BgSurface).border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text("BAND", color = TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${segment.band} · ${formatKHz(segment.loHz).dropLast(2)}–${formatKHz(segment.hiHz).dropLast(2)}",
                    color = TextPrimary, fontSize = 12.sp, fontFamily = GeistMonoFamily,
                )
            }
            SourceHealth(service, now)
        }
        if (showGoRefused(service.goRefusedAtMs, now)) {
            Text("Rig is transmitting — QSY skipped. Tap again after the over.", color = Accent, fontSize = 11.sp)
        }
        // Pips change every second (ages tick); keep the gesture detector stable
        // and read the latest list through state so a tap mid-tick isn't dropped.
        val latestPips = rememberUpdatedState(pips)
        val latestOpen = rememberUpdatedState(onOpenSheet)
        Box(
            Modifier.fillMaxWidth().height(44.dp).pointerInput(service) {
                detectTapGestures { pos ->
                    val f = pos.x / size.width
                    val hit = nearestPip(latestPips.value, f, tolerance = 14.dp.toPx() / size.width)
                    if (hit != null) service.go(hit.spot) else latestOpen.value()
                }
            },
        ) {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                val baseline = size.height - 14f
                drawRect(Border, Offset(0f, baseline), Size(size.width, 1f))
                if (window != null) {
                    val x0 = window.first * size.width
                    val x1 = window.second * size.width
                    drawRoundRect(SignalSoft, Offset(x0, 2f), Size(x1 - x0, baseline), androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                    drawRect(Signal.copy(alpha = 0.55f), Offset(x0, 2f), Size(1f, baseline))
                    drawRect(Signal.copy(alpha = 0.55f), Offset(x1 - 1f, 2f), Size(1f, baseline))
                }
                for (p in pips) {
                    val h = 10f + 16f * p.heightFraction
                    val x = p.fraction * size.width
                    val col = if (p.source == SpotSource.RBN) RbnGreen else TextMuted
                    drawRoundRect(col.copy(alpha = 0.5f + 0.5f * p.heightFraction), Offset(x - 1.5f, baseline - h), Size(3f, h), androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                }
                for (p in labels) {
                    val layout = textMeasurer.measure(p.spot.call, labelStyle)
                    val x = (p.fraction * size.width - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                    drawText(layout, topLeft = Offset(x, baseline + 2f))
                }
                val lo = textMeasurer.measure(formatKHz(segment.loHz).dropLast(2), axisStyle)
                val hi = textMeasurer.measure(formatKHz(segment.hiHz).dropLast(2), axisStyle)
                if (labels.none { it.fraction < 0.2f }) drawText(lo, topLeft = Offset(0f, baseline + 2f))
                if (labels.none { it.fraction > 0.8f }) drawText(hi, topLeft = Offset(size.width - hi.size.width, baseline + 2f))
            }
            RailStateOverlay(service, pips.isEmpty(), onOpenSheet)
        }
    }
}

/** RBN / PSK health at the right of the rail header. */
@Composable
private fun SourceHealth(service: SpotService, now: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val rbnColor = when (service.rbnState) {
            RbnClient.State.LIVE -> RbnGreen
            RbnClient.State.CONNECTING, RbnClient.State.RETRYING -> Accent
            RbnClient.State.OFF -> TextFaint
        }
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(rbnColor))
        Text(
            when (service.rbnState) {
                RbnClient.State.LIVE -> "RBN"
                RbnClient.State.CONNECTING -> "CONNECTING"
                RbnClient.State.RETRYING -> "RBN RETRY"
                RbnClient.State.OFF -> "RBN OFF"
            },
            color = if (service.rbnState == RbnClient.State.LIVE) RbnGreen else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        )
        val pskAge = service.pskLastOkMs?.let { (now - it) / 1000 }
        Text(
            when {
                service.pskOffline && pskAge == null -> "PSK OFFLINE"
                pskAge == null -> "PSK"
                else -> "PSK ${ageLabel(pskAge)}"
            },
            color = if (service.pskOffline) TextFaint else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily,
        )
    }
}

/** Centre-of-rail message for the empty states: connecting, no callsign, quiet band, offline. */
@Composable
private fun RailStateOverlay(service: SpotService, empty: Boolean, onOpenSheet: () -> Unit) {
    if (!empty) return
    val needsCall = service.rbnCallsign.isBlank()
    val connecting = service.rbnState == RbnClient.State.CONNECTING && service.pskLastOkMs == null
    val offline = service.pskOffline && service.rbnState != RbnClient.State.LIVE
    val others = spotsPerBand(service.spots).filter { it.first != service.segment.band }.take(2)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                needsCall && service.spots.isEmpty() -> Text("Set your call in Settings for live RBN", color = TextFaint, fontSize = 11.sp)
                connecting -> Text("Listening for spots…", color = TextFaint, fontSize = 11.sp)
                offline -> Text("Spots offline", color = TextFaint, fontSize = 11.sp)
                others.isNotEmpty() -> {
                    Text("Quiet here.", color = TextFaint, fontSize = 11.sp)
                    others.forEach { (band, n) ->
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(AccentSoft).clickable(onClick = onOpenSheet)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) { Text("$band · $n", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily) }
                    }
                }
                else -> Text("No RTTY spotted on ${service.segment.band} yet", color = TextFaint, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Replaces the rail after a tap-to-go: names the target, what the dial did,
 * and whether the decoder has printed the station yet.
 */
@Composable
fun GoBanner(service: SpotService, go: SpotService.GoTarget, rxText: String, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(go) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val copying = goIsCopying(rxText, service.rxTotalChars, go.rxMark, go.spot.call)
    val settling = now - go.startedMs < QSY_SETTLE_MS && go.plan !is SpotTuner.Plan.TuneOnly
    val status = when {
        copying -> "● copying" to RbnGreen
        settling -> "● QSY…" to Accent
        else -> "○ waiting for decode" to TextMuted
    }
    val detail = when (val p = go.plan) {
        is SpotTuner.Plan.TuneOnly -> "in passband → mark at ${p.audioHz}"
        is SpotTuner.Plan.Qsy -> "dial ${formatKHz(p.dialHz)} → mark at ${p.audioHz}"
        is SpotTuner.Plan.CrossBand -> "${p.band} · dial ${formatKHz(p.dialHz)} → mark at ${p.audioHz}"
    }
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(SignalSoft).border(1.dp, Signal, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text("TUNED", color = Signal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(go.spot.call, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                Text(go.spot.freqLabel, color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(detail, color = TextMuted, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                Text(status.first, color = status.second, fontSize = 11.sp, fontFamily = GeistMonoFamily)
            }
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BgSurface2).clickable { service.dismissGo() },
            contentAlignment = Alignment.Center,
        ) { Text("×", color = TextMuted, fontSize = 18.sp) }
    }
}

private const val QSY_SETTLE_MS = 2_500L

/** One spot's compact summary line: "RBN 24 dB · 45 Bd" or "PSK · heard by 3". */
fun spotSummary(spot: RttySpot): String = when (spot.source) {
    SpotSource.RBN -> buildString {
        append("RBN")
        spot.snrDb?.let { append(" $it dB") }
        spot.baud?.let { append(" · $it Bd") }
    }
    SpotSource.PSK -> "PSK · heard by ${spot.spotters.size}"
}

/** Small filled band tag used on cross-band rows. */
@Composable
internal fun BandTag(band: String) {
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(band, color = BgApp, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
    }
}

/** Width helper so callers can size GO buttons consistently. */
internal val GoButtonWidth = 56.dp
