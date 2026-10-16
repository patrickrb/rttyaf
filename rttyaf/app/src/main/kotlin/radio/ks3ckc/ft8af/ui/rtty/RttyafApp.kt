package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import radio.ks3ckc.ft8af.rtty.InMemoryRttyPrefsStore
import radio.ks3ckc.ft8af.rtty.RttyEngine
import radio.ks3ckc.ft8af.rtty.RttyModePrefs
import radio.ks3ckc.ft8af.rtty.RttyPrefsStore
import radio.ks3ckc.ft8af.rtty.spots.SpotService
import radio.ks3ckc.ft8af.rtty.spots.formatDialLabel
import radio.ks3ckc.ft8af.rtty.spots.RttySegments
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.TextPrimary

/** A logged RTTY contact, kept in memory for the Log screen (DB logging is phase 2). */
data class RttyLogEntry(
    val call: String,
    val band: String,
    val timeUtc: String,
    val rstSent: String,
    val rstRcvd: String,
    /** Exchange we sent (our grid/zone/serial). */
    val exchSent: String = "",
    /** Exchange we received from the worked station — needed for contest mults. */
    val exchRcvd: String = "",
    /** Our running serial number at the time of the QSO. */
    val serial: Int = 0,
    val synced: Boolean = false,
)

/** One programmable RTTY macro (function-key text with {VAR} substitutions). */
data class RttyMacro(val name: String, val key: String, val text: String)

/**
 * Shared, screen-spanning state for the RTTY app: the live [engine] plus the
 * operator entry, macro set and in-memory log. Held once by [RttyafApp] and
 * handed to each screen. Mutable Compose state so edits reflect everywhere.
 */
class RttyAppState(
    val engine: RttyEngine,
    val spots: SpotService,
    private val callsignProvider: () -> String = { "" },
    /** Where the RTTY mode settings persist; in-memory unless the host supplies a real one. */
    private val store: RttyPrefsStore = InMemoryRttyPrefsStore(),
    /** The FT8AF-inherited view model the Radio & Audio / Logging / About settings screens drive; null in tests and previews. */
    val mainViewModel: MainViewModel? = null,
) {
    /** The saved RTTY mode settings (baud/shift/tones/…); edited on Settings → RTTY Mode. */
    var mode by mutableStateOf(store.load())
        private set

    /**
     * Change the mode settings: persist, then apply to the live modem. A change
     * to the tone pair (mark or shift) retunes the decoder to the new nominal
     * pair, dropping any AFC / tap-to-tune offset — that is what an operator
     * who just changed the tones expects. Any other edit (squelch, USOS, baud,
     * sideband) keeps the live tuning so a station being copied isn't lost.
     * The result is normalised so mark + shift always fits the passband.
     */
    fun updateMode(transform: (RttyModePrefs) -> RttyModePrefs) {
        val prev = mode
        val next = transform(prev).normalized()
        if (next == prev) return
        mode = next
        store.save(next)
        if (next.toConfig() != prev.toConfig()) {
            engine.applyConfig(next.toConfig(), keepTune = keepsTune(prev, next))
        }
        engine.afc = next.afc
        engine.net = next.net
    }

    /**
     * Back to the standard settings *and* the standard tones. Unlike
     * [updateMode] this always re-applies the config, so tapping Reset after
     * tuning around the waterfall returns RX to 2125/2295 even when the saved
     * preferences were already the defaults.
     */
    fun resetMode() {
        val defaults = RttyModePrefs()
        mode = defaults
        store.save(defaults)
        engine.applyConfig(defaults.toConfig())
        engine.afc = defaults.afc
        engine.net = defaults.net
    }

    /** Toggle AFC (Operate-screen chip or Settings) and remember the choice. */
    fun setAfc(on: Boolean) {
        engine.afc = on
        if (mode.afc != on) {
            mode = mode.copy(afc = on)
            store.save(mode)
        }
    }

    /** Toggle NET and remember the choice. */
    fun setNet(on: Boolean) {
        engine.net = on
        if (mode.net != on) {
            mode = mode.copy(net = on)
            store.save(mode)
        }
    }

    var call by mutableStateOf("")
    var rstSent by mutableStateOf("599")
    var rstRcvd by mutableStateOf("599")
    var serial by mutableStateOf(1)
    /** Our own exchange sent in {EXCH} macros (grid/zone/etc.); edited on Contest. */
    var myExchange by mutableStateOf("FN20")
    /** The worked station's exchange, typed on Operate and stored with the QSO. */
    var rcvdExchange by mutableStateOf("")
    var txPreview by mutableStateOf("")
    /** Whether the XY tuning scope is shown beside the waterfall. */
    var showXyScope by mutableStateOf(true)

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

    /** Operator callsign from the app's settings, with the design default as a fallback. */
    val myCall: String get() = callsignProvider().trim().uppercase().ifBlank { "KS3CKC" }

    /** "14.084 MHz · 20m", following the rig dial the spot service tracks. */
    val bandLabel: String get() = formatDialLabel(spots.dialHz)
    val bandShort: String get() = RttySegments.bandOf(spots.dialHz) ?: "?"

    /** Upper-case calls already in the in-memory log (greys out worked spots). */
    val workedCalls: Set<String> get() = log.map { it.call.uppercase() }.toSet()

    /** Expand {VAR} tokens in a macro against current entry state. */
    fun expand(text: String): String = text
        .replace("{MYCALL}", myCall)
        .replace("{CALL}", call.ifBlank { "…" })
        .replace("{RST}", rstSent)
        .replace("{EXCH}", myExchange)
        .replace("{SERIAL}", serial.toString().padStart(3, '0'))
}

/** Whether an edit from [prev] to [next] leaves the tone pair alone (so live tuning should be kept). */
internal fun keepsTune(prev: RttyModePrefs, next: RttyModePrefs): Boolean =
    prev.markHz == next.markHz && prev.shiftHz == next.shiftHz

enum class RttyTab { OPERATE, CONTEST, MACROS, LOG, SETTINGS }

/**
 * Top-level RTTYAF UI: a clean scaffold (screen content + bottom nav) matching
 * the RTTYAF.dc.html design, reusing the FT8AF dark theme. Replaces FT8AFApp as
 * the app shell (see ComposeMainActivity.setContent).
 */
/**
 * The backend's operator messages. FT8AF's `ToastMessage.show` is not an Android
 * toast: it posts to `GeneralVariables.mutableDebugMessage`, which the old
 * Activity rendered as a banner. Nothing in the Compose shell observed it, so
 * every refusal — "RTTY TX needs a sound-card audio route", TX audio dropped,
 * band change refused while keyed — was invisible, and a TX tap that did
 * nothing looked like a dead button. Shown above the nav bar for the ~5 s
 * ToastMessage keeps each message alive.
 */
@Composable
private fun StatusBanner() {
    val raw by GeneralVariables.mutableDebugMessage.observeAsState()
    val text = statusBannerText(raw) ?: return
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            // Announce without stealing focus from the TX button a screen
            // reader is probably still on.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clip(RoundedCornerShape(12.dp)).background(BgSurface2)
            .border(1.dp, Accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Banner text for a raw `mutableDebugMessage` payload: null or blank hides the
 * banner (ToastMessage posts the empty join once its last message expires).
 * Pure — unit-tested.
 */
internal fun statusBannerText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

@Composable
fun RttyafApp(
    engine: RttyEngine,
    spots: SpotService,
    callsignProvider: () -> String = { "" },
    store: RttyPrefsStore = InMemoryRttyPrefsStore(),
    mainViewModel: MainViewModel? = null,
) {
    val state = remember(engine, spots) { RttyAppState(engine, spots, callsignProvider, store, mainViewModel) }
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
            StatusBanner()
            RttyNavBar(
                active = tab,
                onSelect = { tab = it },
                bottomInset = insets.calculateBottomPadding(),
            )
        }
    }
}
