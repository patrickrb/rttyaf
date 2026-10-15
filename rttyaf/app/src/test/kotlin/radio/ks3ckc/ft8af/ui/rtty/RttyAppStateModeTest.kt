package radio.ks3ckc.ft8af.ui.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.rtty.InMemoryRttyPrefsStore
import radio.ks3ckc.ft8af.rtty.RttyEngine
import radio.ks3ckc.ft8af.rtty.RttyModePrefs
import radio.ks3ckc.ft8af.rtty.spots.SpotService

/**
 * [RttyAppState]'s mode-settings plumbing: edits persist to the store and are
 * applied to the live engine, and the AFC/NET chips on Operate are remembered.
 */
class RttyAppStateModeTest {

    private fun state(store: InMemoryRttyPrefsStore, engine: RttyEngine = RttyEngine(hamRecorder = null)): RttyAppState {
        val spots = SpotService(engine, qsy = { true }, dialProvider = { 14_084_000L }, callsignProvider = { "KS3CKC" })
        return RttyAppState(engine, spots, store = store)
    }

    @Test
    fun mode_isLoadedFromTheStore() {
        val saved = RttyModePrefs(baudRate = 75.0, afc = true)
        val s = state(InMemoryRttyPrefsStore(saved))
        assertThat(s.mode).isEqualTo(saved)
    }

    @Test
    fun updateMode_persistsAndRetunesTheEngine() {
        val store = InMemoryRttyPrefsStore()
        val engine = RttyEngine(hamRecorder = null)
        val s = state(store, engine)
        engine.tune(1500.0) // an AFC/tap offset that a tone change should drop

        s.updateMode { it.copy(shiftHz = 200, markHz = 1275.0) }

        assertThat(store.current.shiftHz).isEqualTo(200)
        assertThat(store.saveCount).isEqualTo(1)
        assertThat(engine.config.shiftHz).isEqualTo(200)
        assertThat(engine.config.markHz).isEqualTo(1275.0)
    }

    @Test
    fun updateMode_noopWhenNothingChanged() {
        val store = InMemoryRttyPrefsStore()
        val s = state(store)
        s.updateMode { it }
        assertThat(store.saveCount).isEqualTo(0)
    }

    @Test
    fun updateMode_afcAndNetFlagsReachTheEngine_withoutRebuildingTheModem() {
        val store = InMemoryRttyPrefsStore()
        val engine = RttyEngine(hamRecorder = null)
        val s = state(store, engine)
        engine.tune(1500.0)

        s.updateMode { it.copy(afc = true, net = false) }

        assertThat(engine.afc).isTrue()
        assertThat(engine.net).isFalse()
        // Only tuning defaults changed: the live tune must not be reset.
        assertThat(engine.config.markHz).isEqualTo(1500.0)
    }

    @Test
    fun setAfcAndSetNet_updateEngineAndPersist() {
        val store = InMemoryRttyPrefsStore()
        val engine = RttyEngine(hamRecorder = null)
        val s = state(store, engine)
        assertThat(engine.afc).isFalse() // default is off

        s.setAfc(true)
        assertThat(engine.afc).isTrue()
        assertThat(store.current.afc).isTrue()

        s.setNet(false)
        assertThat(engine.net).isFalse()
        assertThat(store.current.net).isFalse()
        assertThat(store.saveCount).isEqualTo(2)

        s.setAfc(true) // already on: no redundant write
        assertThat(store.saveCount).isEqualTo(2)
    }

    @Test
    fun updateMode_nonToneEdit_keepsTheLiveTuning() {
        // Copilot review: tune to a weak station, turn squelch off to copy it —
        // the decoder must stay on the station, not jump back to 2125.
        val store = InMemoryRttyPrefsStore()
        val engine = RttyEngine(hamRecorder = null)
        val s = state(store, engine)
        engine.tune(1500.0)

        s.updateMode { it.copy(squelch = false, unshiftOnSpace = false, baudRate = 75.0) }

        assertThat(engine.config.markHz).isEqualTo(1500.0)
        assertThat(engine.config.squelch).isFalse()
        assertThat(engine.config.baudRate).isEqualTo(75.0)
    }

    @Test
    fun updateMode_shiftChange_resetsToTheNominalPair() {
        val engine = RttyEngine(hamRecorder = null)
        val s = state(InMemoryRttyPrefsStore(), engine)
        engine.tune(1500.0)
        s.updateMode { it.copy(shiftHz = 200) }
        assertThat(engine.config.markHz).isEqualTo(2125.0)
        assertThat(engine.config.shiftHz).isEqualTo(200)
    }

    @Test
    fun updateMode_widenShift_clampsMarkSoSpaceStaysInPassband() {
        val store = InMemoryRttyPrefsStore(RttyModePrefs(markHz = 2800.0))
        val engine = RttyEngine(hamRecorder = null)
        val s = state(store, engine)
        s.updateMode { it.copy(shiftHz = 850) }
        assertThat(store.current.markHz).isEqualTo(2150.0)
        assertThat(engine.config.markHz).isEqualTo(2150.0)
        assertThat(engine.config.spaceHz).isEqualTo(3000.0)
    }

    @Test
    fun resetMode_restoresStandardTonesEvenWhenPrefsWereAlreadyDefault() {
        // Copilot review: with default prefs, tune away then tap Reset — the
        // old early-return left RX at 1500/1670.
        val store = InMemoryRttyPrefsStore()
        val engine = RttyEngine(hamRecorder = null)
        val s = state(store, engine)
        engine.tune(1500.0)
        engine.afc = true

        s.resetMode()

        assertThat(engine.config.markHz).isEqualTo(2125.0)
        assertThat(engine.afc).isFalse()
        assertThat(s.mode).isEqualTo(RttyModePrefs())
        assertThat(store.saveCount).isEqualTo(1)
    }

    @Test
    fun keepsTune_onlyWhenMarkAndShiftAreUntouched() {
        val base = RttyModePrefs()
        assertThat(keepsTune(base, base.copy(squelch = false))).isTrue()
        assertThat(keepsTune(base, base.copy(usbSideband = true))).isTrue()
        assertThat(keepsTune(base, base.copy(markHz = 1275.0))).isFalse()
        assertThat(keepsTune(base, base.copy(shiftHz = 200))).isFalse()
    }

    @Test
    fun engine_startsOnTheSavedModeWhenConstructedFromPrefs() {
        val p = RttyModePrefs(baudRate = 50.0, markHz = 1275.0, afc = true, net = false)
        val engine = RttyEngine(hamRecorder = null, initialConfig = p.toConfig(), initialAfc = p.afc, initialNet = p.net)
        assertThat(engine.config).isEqualTo(p.toConfig())
        assertThat(engine.afc).isTrue()
        assertThat(engine.net).isFalse()
    }
}
