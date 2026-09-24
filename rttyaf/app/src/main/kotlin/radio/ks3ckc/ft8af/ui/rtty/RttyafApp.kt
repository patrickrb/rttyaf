package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import radio.ks3ckc.ft8af.rtty.RttyEngine
import radio.ks3ckc.ft8af.theme.BgApp

/** A logged RTTY contact, kept in memory for the Log screen (DB logging is phase 2). */
data class RttyLogEntry(
    val call: String,
    val band: String,
    val timeUtc: String,
    val rstSent: String,
    val rstRcvd: String,
    val synced: Boolean = false,
)

/** One programmable RTTY macro (function-key text with {VAR} substitutions). */
data class RttyMacro(val name: String, val key: String, val text: String)

/**
 * Shared, screen-spanning state for the RTTY app: the live [engine] plus the
 * operator entry, macro set and in-memory log. Held once by [RttyafApp] and
 * handed to each screen. Mutable Compose state so edits reflect everywhere.
 */
class RttyAppState(val engine: RttyEngine) {
    var call by mutableStateOf("")
    var rstSent by mutableStateOf("599")
    var rstRcvd by mutableStateOf("599")
    var serial by mutableStateOf(1)
    var myExchange by mutableStateOf("FN20")
    var txActive by mutableStateOf(false)
    var txPreview by mutableStateOf("")

    val macros = mutableStateListOf(
        RttyMacro("CQ", "F1", "CQ CQ DE {MYCALL} {MYCALL} CQ K"),
        RttyMacro("ANS", "F2", "{CALL} DE {MYCALL} {MYCALL} K"),
        RttyMacro("RPRT", "F3", "{CALL} {CALL} DE {MYCALL} UR {RST} {RST} {EXCH} {EXCH} K"),
        RttyMacro("TU", "F4", "{CALL} TU 73 DE {MYCALL} SK"),
        RttyMacro("AGN", "F5", "{CALL} AGN? AGN? DE {MYCALL} K"),
        RttyMacro("MY", "F6", "MY CALL {MYCALL} {MYCALL} K"),
        RttyMacro("QRZ", "F7", "QRZ? DE {MYCALL} K"),
        RttyMacro("599", "F8", "{CALL} 599 599 {EXCH} {EXCH} K"),
    )

    val log = mutableStateListOf<RttyLogEntry>()

    val myCall = "KS3CKC"
    val bandLabel = "14.084 MHz · 20m"
    val bandShort = "20m"

    /** Expand {VAR} tokens in a macro against current entry state. */
    fun expand(text: String): String = text
        .replace("{MYCALL}", myCall)
        .replace("{CALL}", call.ifBlank { "…" })
        .replace("{RST}", rstSent)
        .replace("{EXCH}", myExchange)
        .replace("{SERIAL}", serial.toString().padStart(3, '0'))
}

enum class RttyTab { OPERATE, CONTEST, MACROS, LOG, SETTINGS }

/**
 * Top-level RTTYAF UI: a clean scaffold (screen content + bottom nav) matching
 * the RTTYAF.dc.html design, reusing the FT8AF dark theme. Replaces FT8AFApp as
 * the app shell (see ComposeMainActivity.setContent).
 */
@Composable
fun RttyafApp(engine: RttyEngine) {
    val state = remember(engine) { RttyAppState(engine) }
    var tab by rememberSaveable { mutableStateOf(RttyTab.OPERATE) }
    val insets = WindowInsets.systemBars.asPaddingValues()

    Box(Modifier.fillMaxSize().background(BgApp)) {
        Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding())) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    RttyTab.OPERATE -> OperateScreen(state)
                    RttyTab.CONTEST -> ContestScreen(state)
                    RttyTab.MACROS -> MacrosScreen(state)
                    RttyTab.LOG -> LogScreen(state)
                    RttyTab.SETTINGS -> RttySettingsScreen(state)
                }
            }
            RttyNavBar(
                active = tab,
                onSelect = { tab = it },
                bottomInset = insets.calculateBottomPadding(),
            )
        }
    }
}
