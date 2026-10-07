package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.BrandMark
import radio.ks3ckc.ft8af.theme.BrandSpace
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.TextPrimary

/** The tonal role of a wordmark segment — mirrors the launcher icon's FSK motif. */
internal enum class WordmarkTone { BASE, MARK, SPACE }

/** One run of the wordmark rendered in a single tone. */
internal data class WordmarkSegment(val text: String, val tone: WordmarkTone)

/**
 * The RTTYAF wordmark, split into tonal runs: "RTTY" in the base color, then
 * "A" (MARK/violet) and "F" (SPACE/fuchsia) echoing the two-tone mark/space
 * bars of the launcher icon. Concatenating the segments spells RTTYAF.
 */
internal val RttyafWordmarkSegments: List<WordmarkSegment> = listOf(
    WordmarkSegment("RTTY", WordmarkTone.BASE),
    WordmarkSegment("A", WordmarkTone.MARK),
    WordmarkSegment("F", WordmarkTone.SPACE),
)

/**
 * RTTYAF wordmark — Geist Mono 600 in monochrome white with the trailing "AF"
 * split into the violet MARK and fuchsia SPACE brand tones.
 *
 * Use sparingly: splash screen, About screen, share-card surfaces.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 36.sp,
    baseColor: Color = TextPrimary,
    markColor: Color = BrandMark,
    spaceColor: Color = BrandSpace,
) {
    Row(modifier = modifier) {
        RttyafWordmarkSegments.forEach { segment ->
            val color = when (segment.tone) {
                WordmarkTone.BASE -> baseColor
                WordmarkTone.MARK -> markColor
                WordmarkTone.SPACE -> spaceColor
            }
            Text(
                text = segment.text,
                color = color,
                fontSize = fontSize,
                fontWeight = if (segment.tone == WordmarkTone.BASE) FontWeight.SemiBold else FontWeight.Bold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.04.em,
            )
        }
    }
}
