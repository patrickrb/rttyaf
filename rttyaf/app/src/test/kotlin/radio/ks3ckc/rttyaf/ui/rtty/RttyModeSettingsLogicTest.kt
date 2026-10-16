package radio.ks3ckc.rttyaf.ui.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.rttyaf.rtty.RttyModePrefs

/** Pure label/summary helpers behind Settings → RTTY Mode. */
class RttyModeSettingsLogicTest {

    @Test
    fun formatBaud_dropsOnlyAWholeNumberFraction() {
        assertThat(formatBaud(45.45)).isEqualTo("45.45")
        assertThat(formatBaud(50.0)).isEqualTo("50")
        assertThat(formatBaud(75.0)).isEqualTo("75")
    }

    @Test
    fun baudAndShiftLabels_flagTheStandardValue() {
        assertThat(baudLabel(45.45)).isEqualTo("45.45 Bd (standard)")
        assertThat(baudLabel(75.0)).isEqualTo("75 Bd")
        assertThat(shiftLabel(170)).isEqualTo("170 Hz (standard)")
        assertThat(shiftLabel(200)).isEqualTo("200 Hz")
    }

    @Test
    fun toneLabel_namesHighAndLowTonePairs() {
        assertThat(toneLabel(2125.0, 170)).isEqualTo("2125 / 2295 Hz · high tones")
        assertThat(toneLabel(1275.0, 170)).isEqualTo("1275 / 1445 Hz · low tones")
        assertThat(toneLabel(1500.0, 200)).isEqualTo("1500 / 1700 Hz")
    }

    @Test
    fun sidebandLabel_marksLsbAsTheStandard() {
        assertThat(sidebandLabel(false)).isEqualTo("LSB (standard)")
        assertThat(sidebandLabel(true)).isEqualTo("USB")
    }

    @Test
    fun toneChoices_listPresetsThenCustom_andIndexResolvesBoth() {
        val choices = toneChoices(170)
        assertThat(choices).containsExactly("2125 / 2295 Hz · high tones", "1275 / 1445 Hz · low tones", "Custom…").inOrder()
        assertThat(toneChoiceIndex(2125.0)).isEqualTo(0)
        assertThat(toneChoiceIndex(1275.0)).isEqualTo(1)
        assertThat(toneChoiceIndex(1500.0)).isEqualTo(2) // the custom row
    }

    @Test
    fun modeSummary_isTheLandingRowOneLiner() {
        assertThat(modeSummary(RttyModePrefs())).isEqualTo("45.45 Bd · 170 Hz · 2125/2295 · LSB")
        assertThat(modeSummary(RttyModePrefs(baudRate = 75.0, shiftHz = 200, markHz = 1275.0, usbSideband = true)))
            .isEqualTo("75 Bd · 200 Hz · 1275/1475 · USB")
    }
}
