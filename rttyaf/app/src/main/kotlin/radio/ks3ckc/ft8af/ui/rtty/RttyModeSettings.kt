package radio.ks3ckc.ft8af.ui.rtty

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import radio.ks3ckc.ft8af.rtty.RttyModePrefs
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow
import radio.ks3ckc.ft8af.ui.settings.ListPickerDialog
import radio.ks3ckc.ft8af.ui.settings.NumberInputDialog
import radio.ks3ckc.ft8af.ui.settings.SectionDivider
import radio.ks3ckc.ft8af.ui.settings.SettingsDetailScaffold
import radio.ks3ckc.ft8af.ui.settings.SettingsSection

/**
 * Settings → RTTY Mode: the modem parameters an operator expects to find in
 * any RTTY program (MMTTY, fldigi, 2Tone): baud, shift, tone pair, sideband,
 * USOS, squelch, and the AFC / NET start-up defaults.
 *
 * Every change is persisted through [RttyAppState.updateMode] and applied to
 * the live modem immediately, so there's no Save button. The explanatory copy
 * follows rttycontesting.com's *Introduction* so a newcomer gets the standard
 * values (45.45 Bd, 170 Hz, 2125/2295, LSB) without having to know them.
 */
@Composable
fun RttyModeSettings(state: RttyAppState, onBack: () -> Unit) {
    val mode = state.mode
    var dialog by remember { mutableStateOf<ModeDialog?>(null) }

    SettingsDetailScaffold(title = "RTTY Mode", onBack = onBack) {
        SettingsSection(title = "SIGNAL") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = "Baud rate",
                        description = "45.45 Bd is the HF standard (60 wpm)",
                        value = baudLabel(mode.baudRate),
                        onClick = { dialog = ModeDialog.BAUD },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Shift",
                        description = "Mark-to-space spacing; 170 Hz on HF",
                        value = shiftLabel(mode.shiftHz),
                        onClick = { dialog = ModeDialog.SHIFT },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Tones",
                        description = "Mark / space audio. High tones (2125/2295) are standard",
                        value = toneLabel(mode.markHz, mode.shiftHz),
                        onClick = { dialog = ModeDialog.TONES },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Rig sideband",
                        description = "AFSK RTTY runs in LSB so mark is the higher RF frequency. Pick USB only if your rig is set that way",
                        value = sidebandLabel(mode.usbSideband),
                        onClick = { dialog = ModeDialog.SIDEBAND },
                    )
                }
            }
        }

        SettingsSection(title = "DECODER") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = "Unshift on space (USOS)",
                        description = "A space returns to LETTERS so a lost FIGS doesn't garble the next word",
                        toggle = mode.unshiftOnSpace,
                        onToggleChange = { on -> state.updateMode { it.copy(unshiftOnSpace = on) } },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "Squelch",
                        description = "Only print when a carrier is present; off copies weaker signals but prints noise",
                        toggle = mode.squelch,
                        onToggleChange = { on -> state.updateMode { it.copy(squelch = on) } },
                    )
                }
            }
        }

        SettingsSection(title = "TUNING") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = "AFC on start",
                        description = "Automatic frequency control follows a tuned signal's drift. Off keeps the tones exactly where you put them",
                        toggle = mode.afc,
                        onToggleChange = { on -> state.setAfc(on) },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = "NET on start",
                        description = "Transmit on the tones you are receiving on, instead of the nominal pair",
                        toggle = mode.net,
                        onToggleChange = { on -> state.setNet(on) },
                    )
                }
            }
            Text(
                "AFC and NET can also be toggled on the Operate screen; those taps are remembered here.",
                color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                label = "Reset to standard",
                description = "45.45 Bd · 170 Hz · 2125/2295 Hz · LSB · USOS on · squelch on",
                onClick = { state.updateMode { RttyModePrefs() } },
            )
        }
    }

    when (dialog) {
        ModeDialog.BAUD -> ListPickerDialog(
            title = "Baud rate",
            items = RttyModePrefs.BAUD_OPTIONS.map(::baudLabel),
            selectedIndex = RttyModePrefs.BAUD_OPTIONS.indexOf(mode.baudRate),
            onDismiss = { dialog = null },
            onSelect = { i ->
                state.updateMode { it.copy(baudRate = RttyModePrefs.BAUD_OPTIONS[i]) }
                dialog = null
            },
        )
        ModeDialog.SHIFT -> ListPickerDialog(
            title = "Shift",
            items = RttyModePrefs.SHIFT_OPTIONS.map(::shiftLabel),
            selectedIndex = RttyModePrefs.SHIFT_OPTIONS.indexOf(mode.shiftHz),
            onDismiss = { dialog = null },
            onSelect = { i ->
                state.updateMode { it.copy(shiftHz = RttyModePrefs.SHIFT_OPTIONS[i]) }
                dialog = null
            },
        )
        ModeDialog.TONES -> ListPickerDialog(
            title = "Mark tone",
            items = toneChoices(mode.shiftHz),
            selectedIndex = toneChoiceIndex(mode.markHz),
            onDismiss = { dialog = null },
            onSelect = { i ->
                val preset = RttyModePrefs.TONE_PRESETS.getOrNull(i)
                if (preset != null) {
                    state.updateMode { it.copy(markHz = preset) }
                    dialog = null
                } else {
                    dialog = ModeDialog.CUSTOM_TONE
                }
            },
        )
        ModeDialog.CUSTOM_TONE -> NumberInputDialog(
            title = "Custom mark tone",
            suffix = "Hz",
            initialValue = mode.markHz.toInt(),
            min = RttyModePrefs.MIN_MARK_HZ,
            max = RttyModePrefs.MAX_MARK_HZ,
            onDismiss = { dialog = null },
            onSave = { hz ->
                state.updateMode { it.copy(markHz = hz.toDouble()) }
                dialog = null
            },
        )
        ModeDialog.SIDEBAND -> ListPickerDialog(
            title = "Rig sideband",
            items = listOf(sidebandLabel(false), sidebandLabel(true)),
            selectedIndex = if (mode.usbSideband) 1 else 0,
            onDismiss = { dialog = null },
            onSelect = { i ->
                state.updateMode { it.copy(usbSideband = i == 1) }
                dialog = null
            },
        )
        null -> {}
    }
}

private enum class ModeDialog { BAUD, SHIFT, TONES, CUSTOM_TONE, SIDEBAND }

// ---- Pure label helpers (unit-tested) -----------------------------------------

/** "45.45" / "50" / "75" — drop a trailing ".0" but keep real decimals. */
internal fun formatBaud(baud: Double): String =
    if (baud == baud.toLong().toDouble()) baud.toLong().toString() else baud.toString()

internal fun baudLabel(baud: Double): String =
    "${formatBaud(baud)} Bd" + if (baud == 45.45) " (standard)" else ""

internal fun shiftLabel(shiftHz: Int): String =
    "$shiftHz Hz" + if (shiftHz == 170) " (standard)" else ""

/** "2125 / 2295 Hz · high tones" — names the standard pairs, plain numbers otherwise. */
internal fun toneLabel(markHz: Double, shiftHz: Int): String {
    val mark = markHz.toInt()
    val space = (markHz + shiftHz).toInt()
    val name = when (mark) {
        2125 -> " · high tones"
        1275 -> " · low tones"
        else -> ""
    }
    return "$mark / $space Hz$name"
}

internal fun sidebandLabel(usb: Boolean): String = if (usb) "USB" else "LSB (standard)"

/** Picker rows for the mark tone: each preset with its space partner, then a custom entry. */
internal fun toneChoices(shiftHz: Int): List<String> =
    RttyModePrefs.TONE_PRESETS.map { toneLabel(it, shiftHz) } + "Custom…"

/** Index into [toneChoices] for [markHz]; the custom row when it isn't a preset. */
internal fun toneChoiceIndex(markHz: Double): Int {
    val i = RttyModePrefs.TONE_PRESETS.indexOf(markHz)
    return if (i >= 0) i else RttyModePrefs.TONE_PRESETS.size
}

/** One-line summary for the Settings landing row, e.g. "45.45 Bd · 170 Hz · 2125/2295 · LSB". */
internal fun modeSummary(p: RttyModePrefs): String =
    "${formatBaud(p.baudRate)} Bd · ${p.shiftHz} Hz · ${p.markHz.toInt()}/${p.spaceHz.toInt()} · " +
        if (p.usbSideband) "USB" else "LSB"
