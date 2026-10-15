package radio.ks3ckc.ft8af.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The persisted RTTY mode settings: standard defaults, the mapping onto the
 * modem's [RttyConfig], and the store round-trip including garbage tolerance.
 * Pure Kotlin — no Robolectric.
 */
class RttyPrefsTest {

    @Test
    fun defaults_areTheAmateurHfStandard() {
        // rttycontesting.com: 45.45 Bd, 170 Hz shift, 2125/2295 "high tones", rig in LSB.
        val d = RttyModePrefs()
        assertThat(d.baudRate).isEqualTo(45.45)
        assertThat(d.shiftHz).isEqualTo(170)
        assertThat(d.markHz).isEqualTo(2125.0)
        assertThat(d.spaceHz).isEqualTo(2295.0)
        assertThat(d.usbSideband).isFalse()
        assertThat(d.unshiftOnSpace).isTrue()
        assertThat(d.squelch).isTrue()
    }

    @Test
    fun defaults_afcOffAndNetOn() {
        // Tester feedback: auto frequency adjust must be opt-in, so a fresh
        // install keeps the tones exactly at 2125/2295 until the operator asks.
        val d = RttyModePrefs()
        assertThat(d.afc).isFalse()
        assertThat(d.net).isTrue()
    }

    @Test
    fun toConfig_mapsEveryModemField() {
        val p = RttyModePrefs(
            baudRate = 75.0, shiftHz = 200, markHz = 1275.0,
            usbSideband = true, unshiftOnSpace = false, squelch = false,
        )
        val c = p.toConfig()
        assertThat(c.baudRate).isEqualTo(75.0)
        assertThat(c.shiftHz).isEqualTo(200)
        assertThat(c.markHz).isEqualTo(1275.0)
        assertThat(c.sampleRate).isEqualTo(RttyModePrefs.DEFAULT_SAMPLE_RATE)
        assertThat(c.reverse).isTrue()
        assertThat(c.unshiftOnSpace).isFalse()
        assertThat(c.squelch).isFalse()
        assertThat(p.toConfig(sampleRate = 48000).sampleRate).isEqualTo(48000)
    }

    @Test
    fun toConfig_defaultsEqualTheModemDefaults() {
        // The Settings screen's "Reset to standard" must land on exactly what
        // the modem ships with, or the two notions of "default" drift apart.
        assertThat(RttyModePrefs().toConfig()).isEqualTo(RttyConfig())
    }

    @Test
    fun usbSideband_swapsTheAudioTones() {
        // In LSB the mark tone is the lower audio frequency; in USB the
        // received tones arrive swapped, which the modem models as `reverse`.
        val lsb = RttyModePrefs(usbSideband = false).toConfig()
        val usb = RttyModePrefs(usbSideband = true).toConfig()
        assertThat(lsb.markToneHz).isEqualTo(2125.0)
        assertThat(lsb.spaceToneHz).isEqualTo(2295.0)
        assertThat(usb.markToneHz).isEqualTo(2295.0)
        assertThat(usb.spaceToneHz).isEqualTo(2125.0)
    }

    @Test
    fun store_roundTripsEveryField() {
        val p = RttyModePrefs(
            baudRate = 50.0, shiftHz = 425, markHz = 1500.0,
            usbSideband = true, unshiftOnSpace = false, squelch = false, afc = true, net = false,
        )
        val map = p.toStore()
        assertThat(map.keys).containsExactly(
            RttyModePrefs.KEY_BAUD, RttyModePrefs.KEY_SHIFT, RttyModePrefs.KEY_MARK, RttyModePrefs.KEY_USB,
            RttyModePrefs.KEY_USOS, RttyModePrefs.KEY_SQUELCH, RttyModePrefs.KEY_AFC, RttyModePrefs.KEY_NET,
        )
        assertThat(RttyModePrefs.fromStore { map[it] }).isEqualTo(p)
    }

    @Test
    fun fromStore_emptyStoreYieldsDefaults() {
        assertThat(RttyModePrefs.fromStore { null }).isEqualTo(RttyModePrefs())
    }

    @Test
    fun fromStore_garbageFallsBackPerField_andClampsMark() {
        val bad = mapOf(
            RttyModePrefs.KEY_BAUD to "fast",
            RttyModePrefs.KEY_SHIFT to "-170",
            RttyModePrefs.KEY_MARK to "99999",
            RttyModePrefs.KEY_USB to "maybe",
            RttyModePrefs.KEY_AFC to "true",
        )
        val p = RttyModePrefs.fromStore { bad[it] }
        assertThat(p.baudRate).isEqualTo(45.45)
        assertThat(p.shiftHz).isEqualTo(170)
        assertThat(p.markHz).isEqualTo(RttyModePrefs.MAX_MARK_HZ.toDouble())
        assertThat(p.usbSideband).isFalse()
        assertThat(p.afc).isTrue() // the one valid field still applies
        // And whatever came back must build a valid modem config.
        p.toConfig()
        assertThat(RttyModePrefs.fromStore { mapOf(RttyModePrefs.KEY_MARK to "10")[it] }.markHz)
            .isEqualTo(RttyModePrefs.MIN_MARK_HZ.toDouble())
    }

    @Test
    fun maxMarkHz_shrinksWithTheShift_soBothTonesFitThePassband() {
        assertThat(RttyModePrefs.maxMarkHz(170)).isEqualTo(2800.0)  // 2800 + 170 < 3000: the plain cap applies
        assertThat(RttyModePrefs.maxMarkHz(425)).isEqualTo(2575.0)  // 3000 - 425
        assertThat(RttyModePrefs.maxMarkHz(850)).isEqualTo(2150.0)  // 3000 - 850
        assertThat(RttyModePrefs.maxMarkHz(9000)).isEqualTo(RttyModePrefs.MIN_MARK_HZ.toDouble())
    }

    @Test
    fun normalized_clampsTheMarkForTheChosenShift_andIsIdentityWhenAlreadyValid() {
        val wide = RttyModePrefs(markHz = 2800.0, shiftHz = 850).normalized()
        assertThat(wide.markHz).isEqualTo(2150.0)
        assertThat(wide.spaceHz).isEqualTo(3000.0)
        val ok = RttyModePrefs(markHz = 2575.0, shiftHz = 425)
        assertThat(ok.normalized()).isSameInstanceAs(ok)
    }

    @Test
    fun fromStore_normalisesAgainstTheStoredShift() {
        val saved = mapOf(RttyModePrefs.KEY_MARK to "2800", RttyModePrefs.KEY_SHIFT to "425")
        val p = RttyModePrefs.fromStore { saved[it] }
        assertThat(p.markHz).isEqualTo(2575.0)
        assertThat(p.shiftHz).isEqualTo(425)
    }

    @Test
    fun optionLists_containTheStandardsFirst() {
        assertThat(RttyModePrefs.BAUD_OPTIONS[0]).isEqualTo(45.45)
        assertThat(RttyModePrefs.SHIFT_OPTIONS[0]).isEqualTo(170)
        assertThat(RttyModePrefs.TONE_PRESETS[0]).isEqualTo(2125.0)
        assertThat(RttyModePrefs.BAUD_OPTIONS).containsNoDuplicates()
        assertThat(RttyModePrefs.SHIFT_OPTIONS).containsNoDuplicates()
    }

    @Test
    fun inMemoryStore_savesAndCounts() {
        val store = InMemoryRttyPrefsStore()
        assertThat(store.load()).isEqualTo(RttyModePrefs())
        val p = RttyModePrefs(baudRate = 75.0)
        store.save(p)
        assertThat(store.load()).isEqualTo(p)
        assertThat(store.saveCount).isEqualTo(1)
    }
}
