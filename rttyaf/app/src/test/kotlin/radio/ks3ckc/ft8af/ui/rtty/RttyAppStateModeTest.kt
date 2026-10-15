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
    fun engine_startsOnTheSavedModeWhenConstructedFromPrefs() {
        val p = RttyModePrefs(baudRate = 50.0, markHz = 1275.0, afc = true, net = false)
        val engine = RttyEngine(hamRecorder = null, initialConfig = p.toConfig(), initialAfc = p.afc, initialNet = p.net)
        assertThat(engine.config).isEqualTo(p.toConfig())
        assertThat(engine.afc).isTrue()
        assertThat(engine.net).isFalse()
    }
}
