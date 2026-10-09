package radio.ks3ckc.ft8af.rtty.spots

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import radio.ks3ckc.ft8af.rtty.RttyEngine
import java.io.StringReader
import java.io.StringWriter

/** A Reader that hands back one chunk per read(), like a socket does. */
private class ChunkedReader(chunks: List<String>) : java.io.Reader() {
    private val queue = ArrayDeque(chunks)
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val c = queue.removeFirstOrNull() ?: return -1
        c.toCharArray(cbuf, off, 0, c.length)
        return c.length
    }
    override fun close() {}
}

/** Pure coverage for the RTTY spot sources, the merge board, tuning plans and the service. */
class SpotsTest {

    private val now = 1_700_000_000_000L
    private fun spot(call: String, hz: Long, src: SpotSource = SpotSource.RBN, ageS: Long = 0, snr: Int? = 20, spotters: Set<String> = setOf("W3LPL")) =
        RttySpot(call, hz, src, snrDb = snr, baud = 45, spotters = spotters, lastHeardMs = now - ageS * 1000)

    // ---- formatting / segments -------------------------------------------------

    @Test
    fun formatting_freqAndDialLabels() {
        assertThat(formatKHz(14_089_300)).isEqualTo("14.089.3")
        assertThat(formatKHz(7_041_050)).isEqualTo("7.041.0")
        assertThat(formatDialLabel(14_084_000)).isEqualTo("14.084 MHz · 20m")
        assertThat(formatDialLabel(0)).isEqualTo("—")
        assertThat(formatDialLabel(99_000_000)).isEqualTo("99.000 MHz")
    }

    @Test
    fun segments_knownNearestAndFallback() {
        assertThat(RttySegments.bandOf(14_084_000)).isEqualTo("20m")
        assertThat(RttySegments.bandOf(100_000)).isNull()
        assertThat(RttySegments.forDial(14_084_000)).isEqualTo(RttySegment("20m", 14_070_000, 14_099_000))
        // Dial in the 20m band but above the segment: snap to the nearest segment edge within 20 kHz.
        assertThat(RttySegments.forDial(14_110_000).band).isEqualTo("20m")
        assertThat(RttySegments.forDial(14_110_000).loHz).isEqualTo(14_070_000)
        // Far outside any segment: a window around the dial.
        val fb = RttySegments.forDial(14_250_000)
        assertThat(fb.loHz).isEqualTo(14_240_000)
        assertThat(fb.hiHz).isEqualTo(14_260_000)
        assertThat(fb.fractionOf(14_250_000)).isEqualTo(0.5f)
        assertThat(RttySegments.forDial(99_000_000).band).isEqualTo("?")
    }

    // ---- SpotTuner -------------------------------------------------------------

    @Test
    fun tuner_arithmeticAndPlans() {
        assertThat(SpotTuner.dialFor(14_089_300, 2125)).isEqualTo(14_087_175)
        assertThat(SpotTuner.audioOffsetHz(14_085_500, 14_084_000)).isEqualTo(1500)
        assertThat(SpotTuner.isInPassband(14_085_500, 14_084_000)).isTrue()
        assertThat(SpotTuner.isInPassband(14_082_000, 14_084_000)).isFalse()
        assertThat(SpotTuner.isInPassband(14_087_000, 14_084_000)).isFalse()
        assertThat(SpotTuner.isCrossBand(7_041_000, 14_084_000)).isTrue()
        assertThat(SpotTuner.isCrossBand(14_091_000, 14_084_000)).isFalse()

        assertThat(SpotTuner.plan(14_085_500, 14_084_000, 2125)).isEqualTo(SpotTuner.Plan.TuneOnly(1500))
        assertThat(SpotTuner.plan(14_091_000, 14_084_000, 2125)).isEqualTo(SpotTuner.Plan.Qsy(14_088_875, 2125))
        assertThat(SpotTuner.plan(7_041_500, 14_084_000, 2125)).isEqualTo(SpotTuner.Plan.CrossBand(7_039_375, 2125, "40m"))
        // Unknown dial (no rig yet): never cross-band, never tune-only.
        assertThat(SpotTuner.plan(7_041_500, 0, 2125)).isEqualTo(SpotTuner.Plan.Qsy(7_039_375, 2125))
    }

    // ---- RBN parsing -----------------------------------------------------------

    @Test
    fun rbnParser_rttyLine() {
        val s = RbnLineParser.parse("DX de W3LPL-#:   14083.2  K1AF           RTTY  24 dB  45 BPS  CQ      1842Z", now)!!
        assertThat(s.call).isEqualTo("K1AF")
        assertThat(s.freqHz).isEqualTo(14_083_200)
        assertThat(s.snrDb).isEqualTo(24)
        assertThat(s.baud).isEqualTo(45)
        assertThat(s.spotters).containsExactly("W3LPL")
        assertThat(s.isCq).isTrue()
        assertThat(s.source).isEqualTo(SpotSource.RBN)
        assertThat(s.lastHeardMs).isEqualTo(now)
    }

    @Test
    fun rbnParser_ignoresCwAndGarbage_toleratesMissingFields() {
        assertThat(RbnLineParser.parse("DX de W3LPL-#:   14023.0  K1AF           CW    24 dB  22 WPM  CQ      1842Z", now)).isNull()
        assertThat(RbnLineParser.parse("Welcome to the Reverse Beacon Network", now)).isNull()
        assertThat(RbnLineParser.parse("", now)).isNull()
        val s = RbnLineParser.parse("DX de DL8LAS:  7041.5  zl2abc  RTTY  -3 dB  DX  0301Z", now)!!
        assertThat(s.call).isEqualTo("ZL2ABC")
        assertThat(s.spotters).containsExactly("DL8LAS")
        assertThat(s.snrDb).isEqualTo(-3)
        assertThat(s.baud).isNull()
        assertThat(s.isCq).isFalse()
    }

    @Test
    fun rbnAssembler_linesAndLoginPrompt() {
        val a = RbnLineAssembler()
        assertThat(a.feed("Welcome\r\nPlease enter your call: ")).containsExactly("Welcome")
        assertThat(a.loginPromptPending).isTrue()
        a.loginSent()
        assertThat(a.loginPromptPending).isFalse()
        assertThat(a.feed("DX de A:  1 B RTTY 1 dB 0000Z\nDX de")).containsExactly("DX de A:  1 B RTTY 1 dB 0000Z")
        assertThat(a.loginPromptPending).isFalse()
        assertThat(a.feed(" C: 2 D RTTY 0000Z\n")).containsExactly("DX de C: 2 D RTTY 0000Z")
        // Prompt glued to data in one line still counts.
        val b = RbnLineAssembler()
        b.feed("Please enter your call: DX de X: 1 Y RTTY 0000Z\n")
        assertThat(b.loginPromptPending).isTrue()
        assertThat(RbnLineAssembler.isLoginPrompt("login:")).isTrue()
        assertThat(RbnLineAssembler.isLoginPrompt("callsign:")).isTrue()
        assertThat(RbnLineAssembler.isLoginPrompt("hello")).isFalse()
    }

    @Test
    fun rbnSession_logsInAndDeliversSpots() {
        val input = "Welcome to RBN\r\nPlease enter your call: " +
            "DX de W3LPL-#:   14083.2  K1AF   RTTY  24 dB  45 BPS  CQ  1842Z\r\n" +
            "DX de W3LPL-#:   14023.0  K2XX   CW    24 dB  22 WPM  CQ  1842Z\r\n"
        val out = StringWriter()
        val spots = ArrayList<RttySpot>()
        var live = 0
        val saw = RbnClient.runSession(StringReader(input), out, "ks3ckc", { true }, { spots.add(it) }, { now }) { live++ }
        assertThat(saw).isTrue()
        assertThat(out.toString()).isEqualTo("ks3ckc\r\n")
        assertThat(spots.map { it.call }).containsExactly("K1AF")
        assertThat(live).isEqualTo(1)
        // Realistic framing: the prompt arrives as its own packet, spots later.
        val chunked = ChunkedReader(listOf("Welcome\r\n", "Please enter your call: ", "DX de W3LPL-#:   14083.2  K1AF   RTTY  24 dB  45 BPS  CQ  1842Z\r\n"))
        val out2 = StringWriter()
        val spots2 = ArrayList<RttySpot>()
        assertThat(RbnClient.runSession(chunked, out2, "KS3CKC", { true }, { spots2.add(it) }, { now })).isTrue()
        assertThat(out2.toString()).isEqualTo("KS3CKC\r\n")
        assertThat(spots2.map { it.call }).containsExactly("K1AF")
        // Stream with no RTTY: returns false so the client grows its back-off.
        assertThat(RbnClient.runSession(StringReader("call: \nnothing here\n"), StringWriter(), "X", { true }, {}, { now })).isFalse()
        // keepGoing false: reads nothing.
        assertThat(RbnClient.runSession(StringReader(input), StringWriter(), "X", { false }, {}, { now })).isFalse()
    }

    @Test
    fun rbnClient_retriesAfterFailedConnect_thenStops() {
        val states = ArrayList<RbnClient.State>()
        val sleeps = ArrayList<Long>()
        var attempts = 0
        lateinit var client: RbnClient
        client = RbnClient(
            callsign = "KS3CKC", onSpot = {}, onState = { synchronized(states) { states.add(it) } },
            transport = {
                attempts++
                if (attempts == 1) throw java.io.IOException("refused")
                // Second attempt: a working one-shot session, then stop the client.
                RbnClient.Connection(StringReader("Please enter your call: DX de A-#: 14083.2 K1AF RTTY 10 dB 45 BPS CQ 0000Z\n"), StringWriter()) { client.stop() }
            },
            clock = { now },
            sleep = { ms -> sleeps.add(ms); if (sleeps.size > 2) client.stop() },
        )
        client.start()
        // Wait for the worker to finish.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && !states.contains(RbnClient.State.OFF)) Thread.sleep(10)
        assertThat(states).contains(RbnClient.State.RETRYING)
        assertThat(states).contains(RbnClient.State.LIVE)
        assertThat(states.last()).isEqualTo(RbnClient.State.OFF)
        assertThat(sleeps.first()).isEqualTo(RbnClient.INITIAL_BACKOFF_MS)
    }

    // ---- PSK Reporter --------------------------------------------------------------

    private val pskXml = """<?xml version="1.0"?><receptionReports>
        <receptionReport receiverCallsign="DL8LAS" receiverLocator="JO43" senderCallsign="K1AF" frequency="14089300" sNR="12" mode="RTTY" flowStartSeconds="1700000000"/>
        <receptionReport receiverCallsign="G4XYZ" senderCallsign="K1AF" frequency="14089280" sNR="5" mode="RTTY" flowStartSeconds="1700000100"/>
        <receptionReport receiverCallsign="G4XYZ" senderCallsign="W1AW" frequency="14074000" sNR="5" mode="FT8" flowStartSeconds="1700000100"/>
        <receptionReport receiverCallsign="G4XYZ" senderCallsign="BROKEN" sNR="5" mode="RTTY" flowStartSeconds="1700000100"/>
        </receptionReports>"""

    @Test
    fun pskParser_parsesRttyOnly_andBuildsUrl() {
        val spots = PskRttyParser.parse(pskXml)
        assertThat(spots.map { it.call }).containsExactly("K1AF", "K1AF")
        assertThat(spots[0].freqHz).isEqualTo(14_089_300)
        assertThat(spots[0].snrDb).isEqualTo(12)
        assertThat(spots[0].spotters).containsExactly("DL8LAS")
        assertThat(spots[0].lastHeardMs).isEqualTo(1_700_000_000_000L)
        assertThat(spots[0].source).isEqualTo(SpotSource.PSK)
        assertThat(PskRttyParser.queryUrl("https://x/query", 14_065_000, 14_104_000, 900, "a@b.c"))
            .isEqualTo("https://x/query?mode=RTTY&frange=14065000-14104000&flowStartSeconds=-900&rronly=1&appcontact=a%40b.c")
    }

    @Test
    fun pskClient_cooldownWindowChangeAndBackoff() {
        var t = now
        val urls = ArrayList<String>()
        var code = 200
        val client = PskRttyClient(fetcher = { urls.add(it); PskRttyClient.FetchResult(code, if (code == 200) pskXml else null) }, clock = { t })
        assertThat(client.fetch(14_065_000, 14_104_000)).hasSize(2)
        assertThat(client.fetch(14_065_000, 14_104_000)).isNull() // cooldown
        assertThat(client.fetch(7_020_000, 7_055_000)).hasSize(2) // new window bypasses it
        assertThat(urls).hasSize(2)
        t += PskRttyClient.COOLDOWN_MS
        assertThat(client.fetch(7_020_000, 7_055_000)).hasSize(2)
        code = 429
        t += PskRttyClient.COOLDOWN_MS
        assertThat(client.fetch(7_020_000, 7_055_000)).isNull()
        assertThat(client.lastError).contains("429")
        code = 200
        t += PskRttyClient.COOLDOWN_MS
        assertThat(client.fetch(7_020_000, 7_055_000)).isNull() // still backing off
        t += PskRttyClient.RATE_LIMIT_BACKOFF_MS
        assertThat(client.fetch(7_020_000, 7_055_000)).hasSize(2)
        assertThat(client.lastError).isNull()
    }

    @Test
    fun pskClient_httpErrorAndException() {
        var t = now
        val c1 = PskRttyClient(fetcher = { PskRttyClient.FetchResult(500, null) }, clock = { t })
        assertThat(c1.fetch(1, 2)).isNull()
        assertThat(c1.lastError).isEqualTo("http 500")
        val c2 = PskRttyClient(fetcher = { throw java.io.IOException("no net") }, clock = { t })
        assertThat(c2.fetch(1, 2)).isNull()
        assertThat(c2.lastError).isEqualTo("no net")
    }

    // ---- SpotBoard ---------------------------------------------------------------

    @Test
    fun board_mergesSameStation_keepsBestOfBoth() {
        val b = SpotBoard()
        b.add(spot("K1AF", 14_089_300, SpotSource.PSK, ageS = 60, snr = 5, spotters = setOf("G4XYZ")))
        b.add(spot("K1AF", 14_089_250, SpotSource.RBN, ageS = 10, snr = 20, spotters = setOf("W3LPL")))
        b.add(spot("K1AF", 14_089_200, SpotSource.PSK, ageS = 5, snr = 9, spotters = setOf("DL8LAS")))
        assertThat(b.size()).isEqualTo(1)
        val m = b.snapshot(now)[0]
        assertThat(m.source).isEqualTo(SpotSource.RBN)
        assertThat(m.freqHz).isEqualTo(14_089_250) // RBN frequency wins over a newer PSK report
        assertThat(m.snrDb).isEqualTo(20)
        assertThat(m.spotters).containsExactly("G4XYZ", "W3LPL", "DL8LAS")
        assertThat(m.lastHeardMs).isEqualTo(now - 5000)
        // Same call far away in frequency is a different entry (e.g. another band).
        b.add(spot("K1AF", 7_041_000))
        assertThat(b.size()).isEqualTo(2)
    }

    @Test
    fun board_pruneRankAndSegmentFilter() {
        val b = SpotBoard()
        b.addAll(
            listOf(
                spot("OLD", 14_085_000, ageS = 16 * 60),
                spot("PSKNEW", 14_086_000, SpotSource.PSK, ageS = 30),
                spot("RBNFRESH", 14_087_000, ageS = 60, spotters = setOf("A")),
                spot("RBNFRESH2", 14_088_000, ageS = 60, spotters = setOf("A", "B")),
                spot("RBNOLD", 14_089_000, ageS = 5 * 60),
                spot("FORTY", 7_041_000, ageS = 10),
            ),
        )
        assertThat(b.prune(now)).isEqualTo(1)
        val ranked = b.snapshot(now).map { it.call }
        // Fresh RBN first (more skimmers breaks the tie), then everything by recency.
        assertThat(ranked).containsExactly("FORTY", "RBNFRESH2", "RBNFRESH", "PSKNEW", "RBNOLD").inOrder()
        assertThat(b.inSegment(RttySegments.forDial(14_084_000), now).map { it.call }).doesNotContain("FORTY")
        b.clear()
        assertThat(b.size()).isEqualTo(0)
    }

    // ---- goIsCopying ---------------------------------------------------------------

    @Test
    fun goIsCopying_onlyLooksAfterTheMark() {
        assertThat(goIsCopying("CQ DE K1AF K", 0, "K1AF")).isTrue()
        assertThat(goIsCopying("CQ DE K1AF K", 8, "K1AF")).isFalse()
        assertThat(goIsCopying("abc", 99, "K1AF")).isFalse()
        assertThat(goIsCopying("de k1af", 0, "K1AF")).isTrue()
        assertThat(goIsCopying("x", 0, "")).isFalse()
    }

    // ---- SpotService ---------------------------------------------------------------

    private class Fixture(dial: Long = 14_084_000, call: String = "KS3CKC", pskBody: String? = null) {
        var dial = dial
        var call = call
        val qsys = ArrayList<Long>()
        val prefills = ArrayList<String>()
        var rbnStarted = 0
        var rbnOnSpot: ((RttySpot) -> Unit)? = null
        val engine = RttyEngine(hamRecorder = null).also { it.afc = false }
        var t = 1_700_000_000_000L
        val psk = PskRttyClient(fetcher = { PskRttyClient.FetchResult(if (pskBody == null) 500 else 200, pskBody) }, clock = { t })
        val service = SpotService(
            engine = engine, qsy = { qsys.add(it) }, dialProvider = { this.dial }, callsignProvider = { this.call },
            onCallPrefill = { prefills.add(it) }, psk = psk, clock = { t },
            rbnFactory = { c, onSpot, onState ->
                rbnStarted++
                rbnOnSpot = onSpot
                RbnClient(c, onSpot, onState, transport = { throw java.io.IOException("offline") }, sleep = { Thread.sleep(5) })
            },
        )
    }

    @Test
    fun service_tickPublishesDialSpotsAndPsk() = runBlocking {
        val f = Fixture(pskBody = pskXml)
        f.service.tick()
        assertThat(f.service.dialHz).isEqualTo(14_084_000)
        assertThat(f.service.segment.band).isEqualTo("20m")
        assertThat(f.service.spots.map { it.call }).containsExactly("K1AF")
        assertThat(f.service.pskLastOkMs).isEqualTo(f.t)
        assertThat(f.service.pskOffline).isFalse()
        assertThat(f.service.segmentSpots()).hasSize(1)
        assertThat(f.service.passbandSpots()).isEmpty() // 14.0893 is 5.3 kHz above the dial
        f.dial = 14_088_000
        f.service.tick()
        assertThat(f.service.passbandSpots()).hasSize(1)
    }

    @Test
    fun service_pskFailureMarksOffline() = runBlocking {
        val f = Fixture(pskBody = null)
        f.service.tick()
        assertThat(f.service.pskOffline).isTrue()
        assertThat(f.service.pskLastOkMs).isNull()
    }

    @Test
    fun service_goPlans_tuneOnlyQsyAndCrossBand() = runBlocking {
        val f = Fixture()
        f.service.tick()
        // In passband: decoder retunes, rig untouched.
        f.service.go(spot("A1A", 14_085_500))
        assertThat(f.qsys).isEmpty()
        assertThat(f.engine.config.markHz).isEqualTo(1500.0)
        assertThat(f.prefills).containsExactly("A1A")
        assertThat(f.service.goTarget!!.plan).isEqualTo(SpotTuner.Plan.TuneOnly(1500))
        // Same band, out of passband: QSY so it lands on the mark tone.
        f.service.go(spot("B2B", 14_091_000))
        assertThat(f.qsys).containsExactly(14_091_000L - 1500L)
        assertThat(f.engine.config.markHz).isEqualTo(1500.0)
        // Cross band waits for confirmation.
        f.service.go(spot("C3C", 7_041_500))
        assertThat(f.service.pendingCrossBand!!.call).isEqualTo("C3C")
        assertThat(f.qsys).hasSize(1)
        f.service.cancelCrossBand()
        assertThat(f.service.pendingCrossBand).isNull()
        f.service.go(spot("C3C", 7_041_500))
        f.service.confirmCrossBand()
        assertThat(f.qsys.last()).isEqualTo(7_041_500L - 1500L)
        assertThat(f.service.goTarget!!.spot.call).isEqualTo("C3C")
        f.service.dismissGo()
        assertThat(f.service.goTarget).isNull()
        f.service.confirmCrossBand() // nothing pending: no-op
        assertThat(f.qsys).hasSize(2)
    }

    @Test
    fun service_rbnNeedsCallsign_andRestarts() {
        val f = Fixture(call = "")
        f.service.startRbn()
        assertThat(f.rbnStarted).isEqualTo(0)
        assertThat(f.service.rbnState).isEqualTo(RbnClient.State.OFF)
        assertThat(f.service.rbnCallsign).isEmpty()
        f.call = "ks3ckc"
        f.service.startRbn()
        assertThat(f.rbnStarted).isEqualTo(1)
        assertThat(f.service.rbnCallsign).isEqualTo("KS3CKC")
        // RBN spots land on the board.
        f.rbnOnSpot!!.invoke(spot("R1R", 14_083_000))
        assertThat(f.service.board.size()).isEqualTo(1)
        f.service.stop()
        assertThat(f.service.rbnState).isEqualTo(RbnClient.State.OFF)
    }

    @Test
    fun service_startStopIdempotent() {
        val f = Fixture()
        f.service.start()
        f.service.start()
        assertThat(f.rbnStarted).isEqualTo(1)
        f.service.stop()
        f.service.stop()
        assertThat(f.service.rbnState).isEqualTo(RbnClient.State.OFF)
    }
}
