package radio.ks3ckc.ft8af.ui.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.rtty.spots.RttySegment
import radio.ks3ckc.ft8af.rtty.spots.RttySpot
import radio.ks3ckc.ft8af.rtty.spots.SpotSource

/** Pure geometry/filter logic behind the band rail, waterfall tags and the spots sheet. */
class RailModelTest {

    private val now = 1_700_000_000_000L
    private val seg = RttySegment("20m", 14_070_000, 14_099_000)
    private fun spot(call: String, hz: Long, src: SpotSource = SpotSource.RBN, ageS: Long = 0, snr: Int? = 20, spotters: Set<String> = setOf("A")) =
        RttySpot(call, hz, src, snrDb = snr, baud = if (src == SpotSource.RBN) 45 else null, spotters = spotters, lastHeardMs = now - ageS * 1000)

    @Test
    fun pipHeight_freshIsFull_oldShrinksToFloor() {
        assertThat(pipHeightFraction(0)).isEqualTo(1f)
        assertThat(pipHeightFraction(900)).isWithin(1e-6f).of(0.4f)
        assertThat(pipHeightFraction(5000)).isWithin(1e-6f).of(0.4f)
        assertThat(pipHeightFraction(450)).isWithin(1e-6f).of(0.7f)
        assertThat(pipHeightFraction(10, maxAgeSeconds = 0)).isEqualTo(1f)
    }

    @Test
    fun railPips_onlyInSegment_withPositionAndSource() {
        val pips = railPips(listOf(spot("IN", 14_084_500), spot("OUT", 7_041_000), spot("P", 14_099_000, SpotSource.PSK)), seg, now)
        assertThat(pips.map { it.spot.call }).containsExactly("IN", "P").inOrder()
        assertThat(pips[0].fraction).isWithin(1e-6f).of(0.5f)
        assertThat(pips[1].fraction).isEqualTo(1f)
        assertThat(pips[1].source).isEqualTo(SpotSource.PSK)
    }

    @Test
    fun passbandWindow_positionedAndClamped() {
        val w = passbandWindow(seg, 14_084_000)!!
        assertThat(w.first).isWithin(1e-6f).of(14_300f / 29_000f)
        assertThat(w.second).isWithin(1e-6f).of(16_800f / 29_000f)
        assertThat(passbandWindow(seg, 0)).isNull()
        assertThat(passbandWindow(seg, 14_200_000)).isNull()
        val edge = passbandWindow(seg, 14_098_000)!!
        assertThat(edge.second).isEqualTo(1f)
    }

    @Test
    fun labelledPips_respectsGapAndMax() {
        val pips = railPips(listOf(spot("A", 14_070_000), spot("B", 14_071_000), spot("C", 14_080_000), spot("D", 14_090_000), spot("E", 14_099_000)), seg, now)
        val labels = labelledPips(pips, max = 3, minGap = 0.16f)
        assertThat(labels.map { it.spot.call }).containsExactly("A", "C", "D").inOrder()
    }

    @Test
    fun nearestPip_withinTolerance() {
        val pips = railPips(listOf(spot("A", 14_070_000), spot("B", 14_084_500)), seg, now)
        assertThat(nearestPip(pips, 0.49f, 0.05f)!!.spot.call).isEqualTo("B")
        assertThat(nearestPip(pips, 0.3f, 0.05f)).isNull()
        assertThat(nearestPip(emptyList(), 0.3f, 0.05f)).isNull()
    }

    @Test
    fun waterfallTags_passbandOnly_withAudioOffset() {
        val tags = waterfallTags(listOf(spot("A", 14_085_500), spot("B", 14_090_000), spot("C", 14_084_100)), 14_084_000)
        assertThat(tags.map { it.spot.call }).containsExactly("A")
        assertThat(tags[0].audioHz).isEqualTo(1500)
    }

    @Test
    fun filterSpots_eachMode() {
        val all = listOf(spot("A", 14_085_500), spot("B", 14_090_000), spot("C", 7_041_000), spot("D", 14_086_000))
        val worked = setOf("D")
        assertThat(filterSpots(all, SpotFilter.THIS_BAND, seg, 14_084_000, worked).map { it.call }).containsExactly("A", "B", "D")
        assertThat(filterSpots(all, SpotFilter.IN_PASSBAND, seg, 14_084_000, worked).map { it.call }).containsExactly("A", "D")
        assertThat(filterSpots(all, SpotFilter.UNWORKED, seg, 14_084_000, worked).map { it.call }).containsExactly("A", "B", "C")
        assertThat(filterSpots(all, SpotFilter.ALL, seg, 14_084_000, worked)).hasSize(4)
    }

    @Test
    fun spotsPerBand_sortedByCount() {
        val all = listOf(spot("A", 14_085_500), spot("B", 14_090_000), spot("C", 7_041_000), spot("X", 100))
        assertThat(spotsPerBand(all)).containsExactly("20m" to 2, "40m" to 1).inOrder()
    }

    @Test
    fun labels_ageAndSummary() {
        assertThat(ageLabel(18)).isEqualTo("18s")
        assertThat(ageLabel(180)).isEqualTo("3m")
        assertThat(ageLabel(7200)).isEqualTo("2h")
        assertThat(spotSummary(spot("A", 1, SpotSource.RBN, snr = 24))).isEqualTo("RBN 24 dB · 45 Bd")
        assertThat(spotSummary(spot("A", 1, SpotSource.RBN, snr = null).copy(baud = null))).isEqualTo("RBN")
        assertThat(spotSummary(spot("A", 1, SpotSource.PSK, spotters = setOf("X", "Y", "Z")))).isEqualTo("PSK · heard by 3")
    }
}
