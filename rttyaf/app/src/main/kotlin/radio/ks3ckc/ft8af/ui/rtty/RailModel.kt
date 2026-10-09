package radio.ks3ckc.ft8af.ui.rtty

import radio.ks3ckc.ft8af.rtty.spots.RttySegment
import radio.ks3ckc.ft8af.rtty.spots.RttySpot
import radio.ks3ckc.ft8af.rtty.spots.SpotBoard
import radio.ks3ckc.ft8af.rtty.spots.SpotSource
import radio.ks3ckc.ft8af.rtty.spots.SpotTuner
import kotlin.math.abs

/**
 * Pure layout logic for the band rail (the strip above the waterfall that
 * shows the RTTY segment with one pip per spotted station) and for the spot
 * tags drawn on the waterfall. Kept out of the Composables so it is testable.
 */

/** One pip on the rail: where, how tall (freshness) and which colour family (source). */
data class RailPip(val spot: RttySpot, val fraction: Float, val heightFraction: Float, val source: SpotSource)

/** Pip height: 1.0 when just heard, shrinking linearly to [MIN_PIP] at the prune age. */
fun pipHeightFraction(ageSeconds: Long, maxAgeSeconds: Long = SpotBoard.DEFAULT_MAX_AGE_MS / 1000): Float {
    if (maxAgeSeconds <= 0) return 1f
    val t = (ageSeconds.toDouble() / maxAgeSeconds).coerceIn(0.0, 1.0)
    return (1.0 - t * (1.0 - MIN_PIP)).toFloat()
}

private const val MIN_PIP = 0.4

/** Pips for every spot inside [segment]. */
fun railPips(spots: List<RttySpot>, segment: RttySegment, nowMs: Long): List<RailPip> =
    spots.filter { segment.contains(it.freqHz) }.map {
        RailPip(it, segment.fractionOf(it.freqHz), pipHeightFraction(it.ageSeconds(nowMs)), it.source)
    }

/** The rig's audio passband as a fraction window on the rail, or null when the dial is off the segment. */
fun passbandWindow(segment: RttySegment, dialHz: Long): Pair<Float, Float>? {
    if (dialHz <= 0) return null
    val lo = segment.fractionOf(dialHz + SpotTuner.PASSBAND_LO_HZ)
    val hi = segment.fractionOf(dialHz + SpotTuner.PASSBAND_HI_HZ)
    if (hi < 0f || lo > 1f) return null
    return lo.coerceIn(0f, 1f) to hi.coerceIn(0f, 1f)
}

/**
 * Which pips get a callsign label: walk the ranked list and keep any whose
 * position is at least [minGap] (fraction of rail width) from every label
 * already placed, up to [max].
 */
fun labelledPips(pips: List<RailPip>, max: Int = 3, minGap: Float = 0.16f): List<RailPip> {
    val out = ArrayList<RailPip>()
    for (p in pips) {
        if (out.size >= max) break
        if (out.none { abs(it.fraction - p.fraction) < minGap }) out.add(p)
    }
    return out
}

/** The pip nearest to [fraction], if within [tolerance] of it. */
fun nearestPip(pips: List<RailPip>, fraction: Float, tolerance: Float): RailPip? =
    pips.minByOrNull { abs(it.fraction - fraction) }?.takeIf { abs(it.fraction - fraction) <= tolerance }

/** A spot drawn on the waterfall at its audio offset. */
data class WaterfallTag(val spot: RttySpot, val audioHz: Int)

/** Tags for spots whose mark tone is inside the passband at [dialHz]. */
fun waterfallTags(spots: List<RttySpot>, dialHz: Long): List<WaterfallTag> =
    spots.filter { SpotTuner.isInPassband(it.freqHz, dialHz) }
        .map { WaterfallTag(it, SpotTuner.audioOffsetHz(it.freqHz, dialHz).toInt()) }

/** Spot-sheet filters. */
enum class SpotFilter { THIS_BAND, IN_PASSBAND, UNWORKED, ALL }

/** Apply a sheet filter. [worked] is the set of callsigns already in the log (upper-case). */
fun filterSpots(spots: List<RttySpot>, filter: SpotFilter, segment: RttySegment, dialHz: Long, worked: Set<String>): List<RttySpot> =
    when (filter) {
        SpotFilter.THIS_BAND -> spots.filter { it.band == segment.band }
        SpotFilter.IN_PASSBAND -> spots.filter { SpotTuner.isInPassband(it.freqHz, dialHz) }
        SpotFilter.UNWORKED -> spots.filter { it.call.uppercase() !in worked }
        SpotFilter.ALL -> spots
    }

/** Count of spots per band, most active first — the "quiet here, try 20m" suggestion. */
fun spotsPerBand(spots: List<RttySpot>): List<Pair<String, Int>> =
    spots.mapNotNull { it.band }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

/** "18s", "3m", "1h" style age text. */
fun ageLabel(ageSeconds: Long): String = when {
    ageSeconds < 60 -> "${ageSeconds}s"
    ageSeconds < 3600 -> "${ageSeconds / 60}m"
    else -> "${ageSeconds / 3600}h"
}
