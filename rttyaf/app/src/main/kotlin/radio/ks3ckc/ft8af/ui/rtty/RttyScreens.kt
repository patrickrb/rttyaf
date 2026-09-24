package radio.ks3ckc.ft8af.ui.rtty

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.Band20m
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.StatusWarn
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------- LOG ----------------
@Composable
fun LogScreen(state: RttyAppState) {
    Column(Modifier.fillMaxSize().background(BgApp)) {
        ScreenTitle("Log", "${state.log.size} QSOs · RTTY")
        if (state.log.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No QSOs yet — work someone on Operate.", color = TextFaint, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.log) { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgSurface)
                            .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(36.dp).background(Band20m))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(e.call, color = Signal, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
                                Text("${e.band} · RTTY", color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily)
                            }
                            val exch = if (e.exchRcvd.isNotBlank()) " · ${e.exchRcvd}" else ""
                            Text("${e.timeUtc}z · S ${e.rstSent} · R ${e.rstRcvd}$exch", color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(top = 3.dp))
                        }
                        val c = if (e.synced) StatusConfirmed else StatusWarn
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(999.dp)).background(c))
                            Text(if (e.synced) "SYNCED" else "PENDING", color = c, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- MACROS ----------------
@Composable
fun MacrosScreen(state: RttyAppState) {
    Column(Modifier.fillMaxSize().background(BgApp)) {
        ScreenTitle("Macros", "Tap a macro to load it into the TX buffer. Variables fill in at TX time.")
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.macros) { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgSurface)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable { state.txPreview = state.expand(m.text) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.width(34.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(BgApp),
                        contentAlignment = Alignment.Center,
                    ) { Text(m.key, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily) }
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(m.text, color = TextMuted, fontSize = 12.sp, fontFamily = GeistMonoFamily, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

// ---------------- CONTEST (placeholder) ----------------
@Composable
fun ContestScreen(state: RttyAppState) {
    // Multipliers = distinct received exchanges (now that Operate logs them).
    val mults = state.log.mapNotNull { it.exchRcvd.ifBlank { null } }.distinct().size
    val points = state.log.size
    val score = points * maxOf(1, mults)
    Column(Modifier.fillMaxSize().background(BgApp)) {
        ScreenTitle("Contest", "Casual · score, mults and Cabrillo export")
        Row(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(BgSurface)
                .border(1.dp, Border, RoundedCornerShape(14.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ScoreTile("${state.log.size}", "QSOs", Accent)
            ScoreTile("$mults", "Mults", Signal)
            ScoreTile("$points", "Points", Color(0xFFC084FC))
            ScoreTile("$score", "Score", StatusConfirmed)
        }
        // My exchange (sent as {EXCH} in macros). Edited here, not on Operate,
        // so typing a worked station's exchange never overwrites our own.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(BgSurface)
                .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("My exchange", color = TextPrimary, fontSize = 14.sp)
                Text("Sent with every {EXCH} macro", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Box(
                Modifier.width(120.dp).clip(RoundedCornerShape(8.dp)).background(BgSurface2)
                    .border(1.dp, Border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                BasicTextField(
                    value = state.myExchange,
                    onValueChange = { state.myExchange = it.uppercase() },
                    singleLine = true,
                    textStyle = TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Contest picker, serials & Cabrillo — coming next.", color = TextFaint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ScoreTile(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = GeistMonoFamily)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

// ---------------- SETTINGS (placeholder) ----------------
@Composable
fun RttySettingsScreen(state: RttyAppState) {
    Column(Modifier.fillMaxSize().background(BgApp)) {
        ScreenTitle("Settings", "${state.myCall} · ${state.myExchange} · RTTYAF 1.0")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow("Radio & Audio", "Rig connection, keying, levels")
            SettingRow("Logging & Sync", "Cloudlog / Wavelog upload")
            SettingRow("RTTY Mode", "${trimBaud(state.engine.config.baudRate)} Bd · ${state.engine.config.shiftHz} Hz shift")
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Settings sub-screens — coming next.", color = TextFaint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingRow(label: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgSurface)
            .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(label, color = TextPrimary, fontSize = 14.sp)
            Text(desc, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", color = TextFaint, fontSize = 18.sp)
    }
}

private fun trimBaud(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
