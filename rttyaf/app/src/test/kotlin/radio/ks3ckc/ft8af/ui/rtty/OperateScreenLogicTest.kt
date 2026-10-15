package radio.ks3ckc.ft8af.ui.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure helpers behind the Operate screen's tuning readout and XY scope canvas. */
class OperateScreenLogicTest {

    @Test
    fun showTuneReset_onlyOnceOffNominalByAHertz() {
        assertThat(showTuneReset(0.0)).isFalse()
        assertThat(showTuneReset(0.4)).isFalse() // sub-Hz AFC jitter isn't worth a ↺
        assertThat(showTuneReset(1.0)).isTrue()
        assertThat(showTuneReset(-37.0)).isTrue()
    }

    @Test
    fun xyGeometry_centresASquareInsideTheCanvas_andFlipsY() {
        val g = XyScopeGeometry(width = 100f, height = 160f, inset = 3f)
        assertThat(g.cx).isEqualTo(50f)
        assertThat(g.cy).isEqualTo(80f)
        assertThat(g.r).isEqualTo(47f) // limited by the narrower side
        assertThat(g.toCanvas(0f, 0f)).isEqualTo(androidx.compose.ui.geometry.Offset(50f, 80f))
        assertThat(g.toCanvas(1f, 0f)).isEqualTo(androidx.compose.ui.geometry.Offset(97f, 80f))
        assertThat(g.toCanvas(0f, 1f)).isEqualTo(androidx.compose.ui.geometry.Offset(50f, 33f)) // +space draws upward
    }

    @Test
    fun xyGeometry_neverCollapsesToZeroRadius() {
        assertThat(XyScopeGeometry(width = 2f, height = 2f).r).isEqualTo(1f)
    }
}
