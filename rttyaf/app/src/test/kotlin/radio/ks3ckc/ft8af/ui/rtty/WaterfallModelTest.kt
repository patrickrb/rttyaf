package radio.ks3ckc.ft8af.ui.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure geometry/pixel/gesture logic behind the Operate-screen waterfall. */
class WaterfallModelTest {

    // ---- WaterfallViewport --------------------------------------------------

    @Test
    fun viewport_defaultTrimsTheBandEdges_andMapsBothWays() {
        val vp = WaterfallViewport.default()
        assertThat(vp.loHz).isEqualTo(500.0)
        assertThat(vp.hiHz).isEqualTo(2800.0)
        assertThat(vp.fractionOf(500.0)).isEqualTo(0f)
        assertThat(vp.fractionOf(2800.0)).isEqualTo(1f)
        assertThat(vp.hzAt(0.5f)).isWithin(1e-6).of(1650.0)
        assertThat(vp.contains(2125.0)).isTrue()
        assertThat(vp.contains(2900.0)).isFalse()
    }

    @Test
    fun viewport_zoomKeepsThePivotFrequencyUnderTheFingers() {
        val vp = WaterfallViewport(0.0, 3000.0)
        val pivot = vp.hzAt(0.25f)
        val zoomed = vp.zoom(2f, 0.25f)
        assertThat(zoomed.spanHz).isWithin(1e-6).of(1500.0)
        assertThat(zoomed.hzAt(0.25f)).isWithin(1e-6).of(pivot)
    }

    @Test
    fun viewport_zoomAndPanAreClampedToTheBand() {
        val vp = WaterfallViewport(1000.0, 2000.0, maxHz = 3000.0, minSpanHz = 400.0)
        assertThat(vp.zoom(100f, 0.5f).spanHz).isEqualTo(400.0)
        val out = vp.zoom(0.01f, 0.5f)
        assertThat(out.loHz).isEqualTo(0.0)
        assertThat(out.hiHz).isEqualTo(3000.0)
        val panned = vp.pan(5f)
        assertThat(panned.hiHz).isEqualTo(3000.0)
        assertThat(panned.spanHz).isEqualTo(1000.0)
        assertThat(vp.pan(-5f).loHz).isEqualTo(0.0)
        assertThat(vp.pan(0f)).isEqualTo(vp)
        assertThat(vp.zoom(1f, 0.5f)).isEqualTo(vp)
    }

    // ---- axisTicks ------------------------------------------------------------

    @Test
    fun axisTicks_picksCoarserStepsForWiderSpans() {
        assertThat(axisTicks(500.0, 2800.0)).containsExactly(500, 1000, 1500, 2000, 2500).inOrder()
        assertThat(axisTicks(1400.0, 1800.0)).containsExactly(1400, 1500, 1600, 1700, 1800).inOrder()
        assertThat(axisTicks(0.0, 3000.0)).containsExactly(0, 500, 1000, 1500, 2000, 2500, 3000).inOrder()
        assertThat(axisTicks(1000.0, 1000.0)).isEmpty()
        assertThat(axisTicks(1010.0, 1090.0)).isEmpty()
    }

    // ---- WaterfallImage / colour ramp -----------------------------------------

    @Test
    fun image_scrollsOldRowsUp_newestAtBottom() {
        val img = WaterfallImage(3, 2) { if (it > 0.5f) 0xFFFFFFFF.toInt() else 0 }
        img.push(floatArrayOf(1f, 0f, 1f))
        assertThat(img.filledRows).isEqualTo(1)
        assertThat(img.pixel(0, 1)).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(img.pixel(1, 1)).isEqualTo(0)
        img.push(floatArrayOf(0f)) // short column pads with zeros
        assertThat(img.filledRows).isEqualTo(2)
        assertThat(img.pixel(0, 0)).isEqualTo(0xFFFFFFFF.toInt()) // previous row moved up
        assertThat(img.pixel(0, 1)).isEqualTo(0)
        assertThat(img.pixel(2, 1)).isEqualTo(0)
        img.push(floatArrayOf(1f, 1f, 1f))
        assertThat(img.filledRows).isEqualTo(2) // capped at height
    }

    @Test
    fun ramp_transparentAtFloor_signalAtMid_whiteAtTop() {
        val bg = 0xFF000000.toInt()
        val sig = 0xFF00FF00.toInt()
        assertThat(waterfallArgb(0f, bg, sig)).isEqualTo(0)
        assertThat(waterfallArgb(0.5f, bg, sig)).isEqualTo(sig)
        assertThat(waterfallArgb(1f, bg, sig)).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(waterfallArgb(0.25f, bg, sig)).isEqualTo(0xFF008000.toInt())
    }

    @Test
    fun lerpArgb_interpolatesEveryChannelAndClampsT() {
        assertThat(lerpArgb(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f)).isEqualTo(0xFF808080.toInt())
        assertThat(lerpArgb(0x00000000, 0xFF102030.toInt(), 2f)).isEqualTo(0xFF102030.toInt())
        assertThat(lerpArgb(0x00000000, 0xFF102030.toInt(), -1f)).isEqualTo(0)
    }

    // ---- classifyGesture -------------------------------------------------------

    @Test
    fun gesture_tapDragTuneOrPan() {
        val cursors = listOf(100f, 120f)
        assertThat(classifyGesture(300f, 2f, cursors, touchSlopPx = 8f, grabRadiusPx = 20f)).isEqualTo(WaterfallGesture.TAP)
        assertThat(classifyGesture(110f, 30f, cursors, 8f, 20f)).isEqualTo(WaterfallGesture.DRAG_TUNE)
        assertThat(classifyGesture(139f, 30f, cursors, 8f, 20f)).isEqualTo(WaterfallGesture.DRAG_TUNE)
        assertThat(classifyGesture(300f, 30f, cursors, 8f, 20f)).isEqualTo(WaterfallGesture.PAN)
        assertThat(classifyGesture(300f, 30f, emptyList(), 8f, 20f)).isEqualTo(WaterfallGesture.PAN)
    }

    @Test
    fun gesture_travelCountsBothAxes_soAVerticalSwipeIsNotATap() {
        assertThat(gestureTravelPx(10f, 10f, 10f, 40f)).isEqualTo(30f)
        assertThat(gestureTravelPx(0f, 0f, 3f, 4f)).isEqualTo(5f)
        assertThat(gestureTravelPx(5f, 5f, 5f, 5f)).isEqualTo(0f)
        val vertical = gestureTravelPx(300f, 0f, 300f, 40f)
        assertThat(classifyGesture(300f, vertical, listOf(100f), touchSlopPx = 8f, grabRadiusPx = 20f)).isEqualTo(WaterfallGesture.PAN)
    }
}
