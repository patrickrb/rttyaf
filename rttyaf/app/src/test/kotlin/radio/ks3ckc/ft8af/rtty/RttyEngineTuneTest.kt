package radio.ks3ckc.ft8af.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Engine-level tuning semantics added for the XY-scope / NET work: the nominal
 * tone pair from Settings vs. the live (AFC / tap-to-tune) pair, the reset back
 * to nominal, which pair TX uses under NET, and the scope feed.
 */
class RttyEngineTuneTest {

    @Test
    fun nominalMark_comesFromTheInitialConfig_andFollowsApplyConfig() {
        val engine = RttyEngine(hamRecorder = null, initialConfig = RttyConfig(markHz = 1275.0))
        assertThat(engine.nominalMarkHz).isEqualTo(1275.0)
        assertThat(engine.tuneOffsetHz).isEqualTo(0.0)

        engine.applyConfig(RttyConfig(markHz = 2125.0))
        assertThat(engine.nominalMarkHz).isEqualTo(2125.0)
        assertThat(engine.config.markHz).isEqualTo(2125.0)
    }

    @Test
    fun tuneMovesTheLivePair_resetTuneSnapsBackToNominal() {
        val engine = RttyEngine(hamRecorder = null)
        engine.tune(1500.0)
        assertThat(engine.config.markHz).isEqualTo(1500.0)
        assertThat(engine.nominalMarkHz).isEqualTo(2125.0) // nominal untouched by tuning
        assertThat(engine.tuneOffsetHz).isEqualTo(-625.0)

        engine.resetTune()
        assertThat(engine.config.markHz).isEqualTo(2125.0)
        assertThat(engine.tuneOffsetHz).isEqualTo(0.0)
    }

    @Test
    fun resetTune_returnsExactlyToACustomPairAtTheTopOfThePassband() {
        // Copilot review: a saved pair whose space tone sits at the passband
        // edge must come back exactly, not one clamp step lower. Settings
        // normalises mark + shift <= DISPLAY_MAX_HZ (RttyModePrefs.normalized),
        // so the highest legal 425 Hz pair is 2575/3000.
        val nominal = RttyModePrefs(markHz = 2575.0, shiftHz = 425).normalized()
        assertThat(nominal.markHz).isEqualTo(2575.0)
        val engine = RttyEngine(hamRecorder = null, initialConfig = nominal.toConfig())
        engine.tune(1500.0)
        engine.resetTune()
        assertThat(engine.config.markHz).isEqualTo(2575.0)
        assertThat(engine.tuneOffsetHz).isEqualTo(0.0)
        // And an out-of-range request is normalised *before* it ever becomes nominal.
        assertThat(RttyModePrefs(markHz = 2800.0, shiftHz = 425).normalized().markHz).isEqualTo(2575.0)
    }

    @Test
    fun applyConfig_keepTune_updatesNominalButLeavesLiveTuningAlone() {
        val engine = RttyEngine(hamRecorder = null)
        engine.tune(1500.0)
        engine.applyConfig(RttyConfig(squelch = false), keepTune = true)
        assertThat(engine.config.markHz).isEqualTo(1500.0)
        assertThat(engine.config.squelch).isFalse()
        assertThat(engine.nominalMarkHz).isEqualTo(2125.0)
        assertThat(engine.tuneOffsetHz).isEqualTo(-625.0)
    }

    @Test
    fun txConfigFor_netOnFollowsRx_netOffUsesNominal() {
        val live = RttyConfig(markHz = 1500.0, shiftHz = 170)
        assertThat(txConfigFor(live, nominalMarkHz = 2125.0, net = true)).isEqualTo(live)
        val fixed = txConfigFor(live, nominalMarkHz = 2125.0, net = false)
        assertThat(fixed.markHz).isEqualTo(2125.0)
        assertThat(fixed.spaceHz).isEqualTo(2295.0)
        assertThat(fixed.shiftHz).isEqualTo(170) // everything else untouched
    }

    @Test
    fun loopback_feedsTheXyScope_withABalancedCross() {
        val engine = RttyEngine(hamRecorder = null)
        assertThat(engine.xyPoints).isEmpty()
        engine.injectLoopbackTest("RYRYRYRYRYRY")
        val arms = xyArms(engine.xyPoints)
        assertThat(engine.xyPoints.size).isGreaterThan(0)
        assertThat(arms.mark).isGreaterThan(0.35f)
        assertThat(arms.space).isGreaterThan(0.35f)
    }

    @Test
    fun applyConfig_clearsTheScopeTrace() {
        val engine = RttyEngine(hamRecorder = null)
        engine.injectLoopbackTest("RYRY")
        assertThat(engine.xyPoints).isNotEmpty()
        engine.applyConfig(RttyConfig(baudRate = 75.0))
        // The published snapshot is replaced on the next audio; the trace behind
        // it must have restarted so a 45-baud figure never bleeds into 75-baud RX.
        engine.injectLoopbackTest("RYRY")
        assertThat(xyArms(engine.xyPoints).mark).isGreaterThan(0.35f)
    }
}
