package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The wordmark is a thin @Composable over [RttyafWordmarkSegments]; the
 * segmentation (what it spells, how the tones split) is the testable logic.
 */
class WordmarkTest {

    @Test
    fun segments_spell_rttyaf() {
        val text = RttyafWordmarkSegments.joinToString("") { it.text }
        assertThat(text).isEqualTo("RTTYAF")
    }

    @Test
    fun trailing_af_uses_mark_then_space_two_tone() {
        // "RTTY" base, then "A" = MARK (violet), "F" = SPACE (fuchsia).
        assertThat(RttyafWordmarkSegments.map { it.tone }).containsExactly(
            WordmarkTone.BASE,
            WordmarkTone.MARK,
            WordmarkTone.SPACE,
        ).inOrder()

        val mark = RttyafWordmarkSegments.single { it.tone == WordmarkTone.MARK }
        val space = RttyafWordmarkSegments.single { it.tone == WordmarkTone.SPACE }
        assertThat(mark.text).isEqualTo("A")
        assertThat(space.text).isEqualTo("F")
    }
}
