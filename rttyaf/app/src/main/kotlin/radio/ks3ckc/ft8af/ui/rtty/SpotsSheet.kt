package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import radio.ks3ckc.ft8af.rtty.spots.RbnClient
import radio.ks3ckc.ft8af.rtty.spots.RttySpot
import radio.ks3ckc.ft8af.rtty.spots.SpotService
import radio.ks3ckc.ft8af.rtty.spots.SpotSource
import radio.ks3ckc.ft8af.rtty.spots.SpotTuner
import radio.ks3ckc.ft8af.rtty.spots.formatKHz
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.AccentSoft
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.BorderStrong
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.SignalSoft
import radio.ks3ckc.ft8af.theme.TextDim
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary

/**
 * Bottom sheet listing every live spot with filters and a GO button per row.
 * Rows on another band carry a band tag and route through the cross-band
 * confirmation. [worked] greys out stations already in the log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotsSheet(service: SpotService, worked: Set<String>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf(SpotFilter.THIS_BAND) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val segment = service.segment
    val rows = filterSpots(service.spots, filter, segment, service.dialHz, worked)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BgSurface, dragHandle = {
        Box(Modifier.padding(top = 8.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(999.dp)).background(TextDim))
    }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Spots", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${service.segmentSpots().size} on ${segment.band} · ${service.spots.size} all bands",
                        color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val live = service.rbnState == RbnClient.State.LIVE
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(if (live) RbnGreen else TextFaint))
                    Text(if (live) "RBN LIVE" else "RBN OFF", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip("This band", filter == SpotFilter.THIS_BAND) { filter = SpotFilter.THIS_BAND }
                FilterChip("In passband", filter == SpotFilter.IN_PASSBAND) { filter = SpotFilter.IN_PASSBAND }
                FilterChip("Unworked", filter == SpotFilter.UNWORKED) { filter = SpotFilter.UNWORKED }
                FilterChip("All bands", filter == SpotFilter.ALL) { filter = SpotFilter.ALL }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet", color = TextFaint, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows, key = { it.call + it.freqHz }) { spot ->
                        SpotRow(spot, now, service, worked = spot.call.uppercase() in worked) {
                            service.go(spot)
                            onDismiss()
                        }
                    }
                }
            }
            Text(
                "Spots older than 15 min drop off", color = TextFaint, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) SignalSoft else BgSurface2)
            .border(1.dp, if (on) SignalSoft else Border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, color = if (on) Signal else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SpotRow(spot: RttySpot, now: Long, service: SpotService, worked: Boolean, onGo: () -> Unit) {
    val inPassband = SpotTuner.isInPassband(spot.freqHz, service.dialHz)
    val crossBand = service.dialHz > 0 && SpotTuner.isCrossBand(spot.freqHz, service.dialHz)
    val fresh = now - spot.lastHeardMs < 2 * 60_000L
    val stripe = when {
        worked -> TextDim
        spot.source == SpotSource.RBN -> RbnGreen.copy(alpha = if (fresh) 1f else 0.45f)
        else -> TextMuted
    }
    Row(
        Modifier.fillMaxWidth().alpha(if (worked) 0.6f else 1f).clip(RoundedCornerShape(12.dp)).background(BgSurface2)
            .border(1.dp, if (inPassband && !worked) RbnGreen.copy(alpha = 0.35f) else Border, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(stripe))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text(spot.call, color = if (worked) TextMuted else TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                when {
                    crossBand -> BandTag(spot.band ?: "?")
                    worked -> Text("WORKED", color = TextFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    inPassband -> Box(Modifier.clip(RoundedCornerShape(5.dp)).background(AccentSoft).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("IN PASSBAND", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    spot.isCq -> Text("CQ", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Text(formatKHz(spot.freqHz), color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily)
                Text(spotSummary(spot), color = if (spot.source == SpotSource.RBN) RbnGreen else TextMuted, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                val skimmers = if (spot.source == SpotSource.RBN && spot.spotters.size > 1) "${spot.spotters.size} skimmers · " else ""
                Text(skimmers + ageLabel(spot.ageSeconds(now)), color = TextFaint, fontSize = 11.sp, fontFamily = GeistMonoFamily)
            }
        }
        val primary = fresh && !worked && !crossBand
        Box(
            Modifier.width(GoButtonWidth).height(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if (primary) Signal else BgSurface2)
                .border(1.dp, if (primary) Signal else if (crossBand) Accent else if (worked) BorderStrong else Signal, RoundedCornerShape(10.dp))
                .clickable(onClick = onGo),
            contentAlignment = Alignment.Center,
        ) {
            Text("GO", color = if (primary) BgApp else if (crossBand) Accent else if (worked) TextMuted else Signal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Bottom sheet asking the operator before a band change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossBandConfirm(service: SpotService, spot: RttySpot, markToneHz: Int) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val band = spot.band ?: "?"
    val newDial = SpotTuner.dialFor(spot.freqHz, markToneHz)
    ModalBottomSheet(onDismissRequest = { service.cancelCrossBand() }, sheetState = sheetState, containerColor = BgSurface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                    Text("!", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Switch to $band?", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("You're on ${service.segment.band}. Check your antenna and tuner first.", color = TextMuted, fontSize = 12.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgSurface2).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(RbnGreen))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(spot.call, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                        BandTag(band)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(formatKHz(spot.freqHz), color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily)
                        Text(spotSummary(spot), color = RbnGreen, fontSize = 11.sp, fontFamily = GeistMonoFamily)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Dial will be set to", color = TextMuted, fontSize = 12.sp)
                    Text("${formatKHz(newDial)} USB", color = TextPrimary, fontSize = 12.sp, fontFamily = GeistMonoFamily)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Signal lands at", color = TextMuted, fontSize = 12.sp)
                    Text("$markToneHz / ${markToneHz + 170} Hz", color = Signal, fontSize = 12.sp, fontFamily = GeistMonoFamily)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(BgSurface2).border(1.dp, BorderStrong, RoundedCornerShape(12.dp))
                        .clickable { service.cancelCrossBand() },
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1.4f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(Accent).clickable { service.confirmCrossBand() },
                    contentAlignment = Alignment.Center,
                ) { Text("Switch and tune", color = BgApp, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
