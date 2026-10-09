package radio.ks3ckc.ft8af.rtty.spots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.rtty.RttyEngine

/**
 * Orchestrates the spot sources and exposes everything the Operate screen
 * draws as Compose state: the ranked spot list, the rig dial, source health,
 * and the in-flight "go to spot" target.
 *
 * - RBN: a persistent telnet session (needs a callsign to log in).
 * - PSK Reporter: polled every five minutes for the current RTTY segment, and
 *   immediately when the dial moves to a different segment.
 * - A 1 s tick refreshes the dial, prunes stale spots and republishes the list.
 *
 * "Go" follows [SpotTuner.plan]: in-passband spots just retune the decoder;
 * same-band spots QSY the rig via [qsy] so the signal lands on the mark tone;
 * cross-band spots wait in [pendingCrossBand] for the operator to confirm.
 */
class SpotService(
    private val engine: RttyEngine,
    /** Move the rig dial; returns false when the rig refused (e.g. mid-transmit). */
    private val qsy: (Long) -> Boolean,
    private val dialProvider: () -> Long,
    private val callsignProvider: () -> String,
    private val onCallPrefill: (String) -> Unit = {},
    private val psk: PskRttyClient = PskRttyClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rbnFactory: (String, (RttySpot) -> Unit, (RbnClient.State) -> Unit) -> RbnClient =
        { call, onSpot, onState -> RbnClient(call, onSpot, onState) },
) {
    val board = SpotBoard()

    /** Every live spot, ranked (see [SpotBoard.rank]). */
    var spots by mutableStateOf<List<RttySpot>>(emptyList())
        private set

    var dialHz by mutableStateOf(0L)
        private set

    val segment: RttySegment get() = RttySegments.forDial(dialHz)

    var rbnState by mutableStateOf(RbnClient.State.OFF)
        private set

    /** Null until the first successful PSK fetch; then the age of the latest one. */
    var pskLastOkMs by mutableStateOf<Long?>(null)
        private set

    /** True once a PSK fetch has failed with no later success (shown as "offline"). */
    var pskOffline by mutableStateOf(false)
        private set

    var goTarget by mutableStateOf<GoTarget?>(null)
        private set

    var pendingCrossBand by mutableStateOf<RttySpot?>(null)
        private set

    /** When the rig last refused a go's QSY (it was transmitting), for a brief notice; null otherwise. */
    var goRefusedAtMs by mutableStateOf<Long?>(null)
        private set

    /** Callsign RBN will log in with, or blank when none is configured. */
    val rbnCallsign: String get() = callsignProvider().trim().uppercase()

    /** Decoder character count, the monotonic clock [GoTarget.rxMark] is measured on. */
    val rxTotalChars: Long get() = engine.rxTotalChars

    private var scope: CoroutineScope? = null
    private var rbn: RbnClient? = null
    private var tickJob: Job? = null

    /** The segment the last PSK request covered, and when any PSK request was last issued. */
    private var pskSegment: RttySegment? = null
    private var lastPskAttemptMs: Long = 0L

    /**
     * An in-flight go. [rxMark] is the engine's [RttyEngine.rxTotalChars] at go
     * time, so "text since the go" survives the RX buffer trimming its front.
     */
    data class GoTarget(val spot: RttySpot, val startedMs: Long, val plan: SpotTuner.Plan, val rxMark: Long)

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        startRbn()
        tickJob = s.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        rbn?.stop()
        rbn = null
        scope?.cancel()
        scope = null
        rbnState = RbnClient.State.OFF
    }

    /** (Re)start the RBN link, e.g. after the operator sets their callsign. */
    fun startRbn() {
        rbn?.stop()
        rbn = null
        val call = rbnCallsign
        if (call.isBlank()) {
            rbnState = RbnClient.State.OFF
            return
        }
        rbn = rbnFactory(call, { spot -> board.add(spot) }, { st -> rbnState = st }).also { it.start() }
    }

    /**
     * One scheduler tick: refresh dial, prune, publish, and poll PSK when due.
     * Also usable from tests.
     *
     * PSK is due when the dial has left the segment the last request covered
     * (not merely moved: outside the known RTTY segments [RttySegments.forDial]
     * synthesises a window around the dial, which would otherwise count as a
     * new segment on every kHz), when nothing has succeeded yet, or every
     * [PskRttyClient.COOLDOWN_MS]. However due, requests are never issued
     * closer together than [PSK_MIN_INTERVAL_MS], so spinning the dial across
     * a band can't hammer the service.
     */
    suspend fun tick() {
        val now = clock()
        dialHz = dialProvider()
        board.prune(now)
        val seg = segment
        publish(now)
        val leftSegment = pskSegment?.contains(dialHz) != true
        val due = leftSegment || pskLastOkMs == null || now - (pskLastOkMs ?: 0L) >= PskRttyClient.COOLDOWN_MS
        if (due && now - lastPskAttemptMs >= PSK_MIN_INTERVAL_MS) {
            lastPskAttemptMs = now
            pskSegment = seg
            val result = withContext(Dispatchers.IO) { psk.fetch(seg.loHz - PSK_MARGIN_HZ, seg.hiHz + PSK_MARGIN_HZ) }
            if (result != null) {
                board.addAll(result)
                pskLastOkMs = clock()
                pskOffline = false
                publish(clock())
            } else if (psk.lastError != null) {
                pskOffline = true
            }
        }
    }

    private fun publish(now: Long) {
        spots = board.snapshot(now)
    }

    /** Spots on the current band's RTTY segment, ranked. */
    fun segmentSpots(): List<RttySpot> {
        val seg = segment
        return spots.filter { seg.contains(it.freqHz) }
    }

    /** Spots whose mark tone currently falls inside the audio passband. */
    fun passbandSpots(): List<RttySpot> = spots.filter { SpotTuner.isInPassband(it.freqHz, dialHz) }

    /** Tap-to-go entry point from the rail, the sheet, or a waterfall tag. */
    fun go(spot: RttySpot) {
        when (val plan = SpotTuner.plan(spot.freqHz, dialHz, engine.config.markHz.toInt())) {
            is SpotTuner.Plan.CrossBand -> pendingCrossBand = spot
            else -> perform(spot, plan)
        }
    }

    fun confirmCrossBand() {
        val spot = pendingCrossBand ?: return
        pendingCrossBand = null
        perform(spot, SpotTuner.plan(spot.freqHz, dialHz, engine.config.markHz.toInt()))
    }

    fun cancelCrossBand() {
        pendingCrossBand = null
    }

    fun dismissGo() {
        goTarget = null
    }

    /**
     * Carry out a plan. A dial move the rig refuses (it is keyed) aborts the go
     * entirely: the decoder is left where it was, no banner is shown and the
     * CALL field is not touched, so nothing claims a QSY that did not happen.
     */
    private fun perform(spot: RttySpot, plan: SpotTuner.Plan) {
        val (newDial, audioHz) = when (plan) {
            is SpotTuner.Plan.TuneOnly -> null to plan.audioHz
            is SpotTuner.Plan.Qsy -> plan.dialHz to plan.audioHz
            is SpotTuner.Plan.CrossBand -> plan.dialHz to plan.audioHz
        }
        if (newDial != null && !qsy(newDial)) {
            goRefusedAtMs = clock()
            return
        }
        goRefusedAtMs = null
        engine.tune(audioHz.toDouble())
        goTarget = GoTarget(spot, clock(), plan, engine.rxTotalChars)
        onCallPrefill(spot.call)
    }

    companion object {
        const val TICK_MS = 1_000L
        /** Query a little beyond the segment so edge activity is included. */
        const val PSK_MARGIN_HZ = 5_000L
        /** Floor between any two PSK Reporter requests, whatever the dial does. */
        const val PSK_MIN_INTERVAL_MS = 60_000L
    }
}

/**
 * Has the decoder printed [call] since the go started? [rxText] is the live RX
 * buffer, [rxTotalChars] the engine's running character count now, and
 * [rxMark] that count at go time — so the text since the go is the last
 * `rxTotalChars - rxMark` characters of the buffer, however much of its front
 * has been trimmed meanwhile. Pure — unit-tested.
 */
fun goIsCopying(rxText: String, rxTotalChars: Long, rxMark: Long, call: String): Boolean {
    if (call.isBlank()) return false
    val since = (rxTotalChars - rxMark).coerceIn(0L, rxText.length.toLong()).toInt()
    return rxText.takeLast(since).contains(call, ignoreCase = true)
}
