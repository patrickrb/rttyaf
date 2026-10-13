package radio.ks3ckc.ft8af.rtty.spots

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * PSK Reporter as an RTTY spot source: who has been *heard* on RTTY in a
 * frequency window recently. The retrieval API is queried by `mode=RTTY` and
 * `frange=lo-hi` with no callsign, so it covers everyone, not just us.
 */

/** Pure parser for `<receptionReport …/>` elements. Regex-based so it runs without Android's XmlPullParser. */
object PskRttyParser {
    private val REPORT = Regex("""<receptionReport\b([^>]*)/?>""")
    private val ATTR = Regex("""(\w+)="([^"]*)"""")

    fun parse(xml: String): List<RttySpot> {
        val out = ArrayList<RttySpot>()
        for (m in REPORT.findAll(xml)) {
            val attrs = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val sender = attrs["senderCallsign"]?.uppercase() ?: continue
            val receiver = attrs["receiverCallsign"]?.uppercase() ?: continue
            val freq = attrs["frequency"]?.toLongOrNull() ?: continue
            val flow = attrs["flowStartSeconds"]?.toLongOrNull() ?: continue
            val mode = attrs["mode"] ?: "RTTY"
            if (!mode.equals("RTTY", ignoreCase = true)) continue
            out.add(
                RttySpot(
                    call = sender,
                    freqHz = freq,
                    source = SpotSource.PSK,
                    snrDb = attrs["sNR"]?.toIntOrNull(),
                    spotters = setOf(receiver),
                    lastHeardMs = flow * 1000L,
                ),
            )
        }
        return out
    }

    /** Build the retrieval URL for an RTTY window. Pure — unit-tested. */
    fun queryUrl(baseUrl: String, loHz: Long, hiHz: Long, secondsBack: Int, appContact: String): String =
        "$baseUrl?mode=RTTY&frange=$loHz-$hiHz&flowStartSeconds=-$secondsBack&rronly=1" +
            "&appcontact=${URLEncoder.encode(appContact, StandardCharsets.UTF_8.name())}"
}

/**
 * Polls PSK Reporter for RTTY reception reports in a frequency window. Honours
 * the service's "no more than once per five minutes for the same query" rule
 * with a client-side cooldown and a longer back-off on 429/503. The HTTP
 * fetch is injectable for tests.
 */
class PskRttyClient(
    private val fetcher: (String) -> FetchResult = ::httpGet,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val baseUrl: String = "https://retrieve.pskreporter.info/query",
) {
    data class FetchResult(val httpCode: Int, val body: String?)

    var lastFetchMs: Long = 0L
        private set
    var backoffUntilMs: Long = 0L
        private set
    var lastError: String? = null
        private set

    /** The window last queried, so a dial move to a new segment forces a fresh query. */
    private var lastWindow: Pair<Long, Long>? = null

    /**
     * Fetch spots for [loHz]..[hiHz]. Returns null when skipped (cooldown,
     * back-off) or failed; an empty list is a successful empty answer.
     */
    fun fetch(loHz: Long, hiHz: Long, secondsBack: Int = 900): List<RttySpot>? {
        val now = clock()
        if (now < backoffUntilMs) return null
        val window = loHz to hiHz
        if (window == lastWindow && lastFetchMs != 0L && now - lastFetchMs < COOLDOWN_MS) return null
        lastFetchMs = now
        lastWindow = window
        val url = PskRttyParser.queryUrl(baseUrl, loHz, hiHz, secondsBack, APP_CONTACT)
        val r = try {
            fetcher(url)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            return null
        }
        if (r.httpCode == 429 || r.httpCode == 503) {
            backoffUntilMs = now + RATE_LIMIT_BACKOFF_MS
            lastError = "rate-limited (${r.httpCode})"
            return null
        }
        if (r.httpCode != 200 || r.body == null) {
            lastError = "http ${r.httpCode}"
            return null
        }
        lastError = null
        return PskRttyParser.parse(r.body)
    }

    companion object {
        const val COOLDOWN_MS = 5L * 60_000L
        const val RATE_LIMIT_BACKOFF_MS = 15L * 60_000L
        const val APP_CONTACT = "rttyaf@example.org"
        private const val IO_TIMEOUT_MS = 8000

        fun httpGet(url: String): FetchResult {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = IO_TIMEOUT_MS
                    readTimeout = IO_TIMEOUT_MS
                    setRequestProperty("User-Agent", "rttyaf-1.0")
                }
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText) else null
                FetchResult(code, body)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
