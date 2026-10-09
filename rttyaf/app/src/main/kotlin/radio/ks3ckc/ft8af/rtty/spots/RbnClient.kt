package radio.ks3ckc.ft8af.rtty.spots

import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent telnet client for the Reverse Beacon Network CW/RTTY feed.
 *
 * Connects to `telnet.reversebeacon.net:7000`, answers the callsign prompt,
 * and hands every RTTY spot line to [onSpot]. Reconnects with exponential
 * back-off (5 s → 2 min) when the link drops. The transport is injectable so
 * [runSession] can be unit-tested against an in-memory reader/writer.
 */
class RbnClient(
    private val callsign: String,
    private val onSpot: (RttySpot) -> Unit,
    private val onState: (State) -> Unit = {},
    private val transport: () -> Connection = { socketConnection(DEFAULT_HOST, DEFAULT_PORT) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    enum class State { OFF, CONNECTING, LIVE, RETRYING }

    /** One open link: text in, text out, and a way to close. */
    class Connection(val reader: Reader, val writer: Writer, val close: () -> Unit)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var current: Connection? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "rbn-telnet").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        // Detach before closing so a close hook that itself calls stop() can't recurse.
        val c = current
        current = null
        try { c?.close?.invoke() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
        onState(State.OFF)
    }

    private fun loop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get()) {
            onState(State.CONNECTING)
            val conn = try { transport() } catch (e: Exception) { null }
            if (conn == null) {
                onState(State.RETRYING)
                if (!pause(backoffMs)) break
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            current = conn
            val sawSpots = try {
                runSession(conn.reader, conn.writer, callsign, running::get, onSpot, clock) { onState(State.LIVE) }
            } catch (_: Exception) {
                false
            } finally {
                try { conn.close() } catch (_: Exception) {}
                current = null
            }
            if (!running.get()) break
            // A session that worked resets the back-off; a dud grows it.
            backoffMs = if (sawSpots) INITIAL_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            onState(State.RETRYING)
            if (!pause(backoffMs)) break
        }
        onState(State.OFF)
    }

    /** Sleep unless stopped; false when interrupted by [stop]. */
    private fun pause(ms: Long): Boolean = try {
        sleep(ms)
        running.get()
    } catch (_: InterruptedException) {
        false
    }

    companion object {
        const val DEFAULT_HOST = "telnet.reversebeacon.net"
        const val DEFAULT_PORT = 7000
        const val INITIAL_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 120_000L
        private const val CONNECT_TIMEOUT_MS = 10_000

        /**
         * Drive one telnet session until the stream ends or [keepGoing] turns
         * false: answer the login prompt with [callsign], parse spot lines, call
         * [onLive] once the first line arrives after login. Returns true if at
         * least one RTTY spot was delivered. Pure given its arguments — tested
         * with a StringReader.
         */
        fun runSession(
            reader: Reader,
            writer: Writer,
            callsign: String,
            keepGoing: () -> Boolean,
            onSpot: (RttySpot) -> Unit,
            clock: () -> Long,
            onLive: () -> Unit = {},
        ): Boolean {
            val assembler = RbnLineAssembler()
            val buf = CharArray(1024)
            var loggedIn = false
            var live = false
            var sawSpot = false
            while (keepGoing()) {
                val n = reader.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val lines = assembler.feed(String(buf, 0, n))
                if (!loggedIn && assembler.loginPromptPending) {
                    writer.write("$callsign\r\n")
                    writer.flush()
                    assembler.loginSent()
                    loggedIn = true
                }
                for (line in lines) {
                    if (loggedIn && !live) { live = true; onLive() }
                    val spot = RbnLineParser.parse(line, clock()) ?: continue
                    sawSpot = true
                    onSpot(spot)
                }
            }
            return sawSpot
        }

        fun socketConnection(host: String, port: Int): Connection {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.keepAlive = true
            val reader = InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
            return Connection(reader, writer) { try { socket.close() } catch (_: Exception) {} }
        }
    }
}
