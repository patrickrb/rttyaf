import { useEffect, useRef, useState, type MouseEvent } from "react";
import {
  api,
  onEngineEvent,
  type AudioDevice,
  type BandInfo,
  type ClockSyncEvent,
  type EngineEvent,
  type HamlibRig,
  type QsoRecord,
  type RigConfig,
  type RigStatusEvent,
  type SerialPortInfo,
  type RttyConfig,
  type TxStateEvent,
  type WaterfallConfig,
  type WfWindow,
} from "./ipc";

type Tab = "decode" | "log" | "settings";
const DEFAULT_MODEM: RttyConfig = { baud_rate: 45.45, shift_hz: 170, mark_hz: 2125,
  sample_rate: 12000, stop_bits: 1.5, reverse: false, unshift_on_space: true,
  squelch: true, squelch_floor: 0.004 };

// The live waterfall draws imperatively to a canvas (registered by WaterfallView)
// so per-row spectrum events never go through React state.
const WF_HEIGHT = 160;
// Top of the displayed audio band; matches the backend's WF_MAX_HZ.
const WF_TOP_HZ = 3500;
let wfCanvas: HTMLCanvasElement | null = null;
function registerWfCanvas(c: HTMLCanvasElement | null) {
  wfCanvas = c;
  if (c) c.height = WF_HEIGHT;
}
function drawWaterfallRow(bins: number[]) {
  const cv = wfCanvas;
  if (!cv || bins.length === 0) return;
  const cols = bins.length;
  if (cv.width !== cols) cv.width = cols;
  const ctx = cv.getContext("2d");
  if (!ctx) return;
  const h = cv.height;
  // Scroll the existing image down by one row, then paint the new row on top.
  ctx.drawImage(cv, 0, 0, cols, h - 1, 0, 1, cols, h - 1);
  const img = ctx.createImageData(cols, 1);
  for (let i = 0; i < cols; i++) {
    const [r, g, b] = colormap(bins[i]);
    img.data[i * 4] = r;
    img.data[i * 4 + 1] = g;
    img.data[i * 4 + 2] = b;
    img.data[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
}

export default function App() {
  const [tab, setTab] = useState<Tab>("decode");
  const [received, setReceived] = useState("");
  const [utcMs, setUtcMs] = useState<number | null>(null);
  const [txState, setTxState] = useState<TxStateEvent | null>(null);
  const [rig, setRig] = useState<RigStatusEvent | null>(null);
  const [clock, setClock] = useState<ClockSyncEvent | null>(null);
  const [decoding, setDecoding] = useState(false);
  const [audio, setAudio] = useState<{ db: number; silent: boolean } | null>(null);
  const [status, setStatus] = useState("");
  const [modem, setModem] = useState<RttyConfig>(DEFAULT_MODEM);
  const [txGain, setTxGain] = useState(90); // TX level as a percentage (0–100)
  const [rigLabel, setRigLabel] = useState(""); // optional display-name override
  const [bands, setBands] = useState<BandInfo[]>([]);
  const [dialHz, setDialHz] = useState<number>(14085000);

  // keep latest decoding flag for the toggle without stale closure
  const decodingRef = useRef(decoding);
  decodingRef.current = decoding;

  useEffect(() => {
    // Register the event listener with cancellation-safe cleanup. Without this,
    // React StrictMode's mount/unmount/mount in dev leaks the first listener
    // (its async `listen()` resolves after the first cleanup ran), so every
    // event would be handled twice — duplicating each decode batch.
    let unlisten: (() => void) | undefined;
    let cancelled = false;
    onEngineEvent(handleEvent).then((u) => {
      if (cancelled) u();
      else unlisten = u;
      // Pull current rig/TX status now that we're listening. The engine
      // auto-reconnects the saved rig at startup and emits its status before this
      // listener exists, so without this the corner would show "no rig" until the
      // next status change.
      api.refreshStatus();
    });
    api.listBands().then(setBands);
    api.getConfig("dial_hz").then((v) => {
      if (v) setDialHz(parseInt(v, 10));
    });
    api.getConfig("tx_gain").then((v) => {
      if (v == null || v === "") return;
      const pct = Math.round(parseFloat(v) * 100);
      if (!Number.isNaN(pct)) setTxGain(pct); // keep a persisted 0% (not just falsy-skip)
    });
    api.getConfig("rig_label").then((v) => {
      if (v) setRigLabel(v);
    });
    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, []);

  function handleEvent(e: EngineEvent) {
    switch (e.type) {
      case "received":
        setReceived((prev) => (prev + e.data).slice(-32000));
        break;
      case "clock":
        setUtcMs(e.data);
        break;
      case "modem":
        setModem(e.data);
        break;
      case "decoding":
        setDecoding(e.data);
        if (!e.data) setAudio(null);
        break;
      case "tx_state":
        setTxState(e.data);
        break;
      case "rig_status":
        setRig(e.data);
        setDialHz(e.data.frequency_hz);
        break;
      case "clock_sync":
        setClock(e.data);
        break;
      case "waterfall_row":
        drawWaterfallRow(e.data.bins);
        break;
      case "input_level":
        setAudio(e.data);
        break;
      case "info":
        setStatus(e.data);
        break;
      case "error":
        setStatus("⚠ " + e.data);
        break;
    }
  }

  function toggleDecode() {
    if (decodingRef.current) {
      api.stopDecode();
    } else {
      api.startDecode();
    }
  }

  function onBand(hz: number) {
    setDialHz(hz);
    api.setBand(hz);
  }

  return (
    <>
      <TopBar
        utcMs={utcMs}
        decoding={decoding}
        onToggleDecode={toggleDecode}
        bands={bands}
        dialHz={dialHz}
        onBand={onBand}
        rig={rig}
        clock={clock}
        audio={audio}
        rigLabel={rigLabel}
        txGain={txGain}
        onTxGain={(v) => {
          setTxGain(v);
          api.setTxGain(v / 100);
        }}
      />
      <div className="tabs">
        {(["decode", "log", "settings"] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
            {t === "decode" ? "Decode" : t === "log" ? "Logbook" : "Settings"}
          </button>
        ))}
      </div>
      <div className={"content" + (tab === "decode" ? " decode" : "")}>
        {tab === "decode" && (
          <DecodeScreen
            received={received}
            onClear={() => setReceived("")}
            modem={modem}
            onModem={(next) => { setModem(next); api.setModem(next); }}
          />
        )}
        {tab === "log" && <LogScreen dialHz={dialHz} utcMs={utcMs} bands={bands} />}
        {tab === "settings" && (
          <SettingsScreen
            onStatus={setStatus}
            clock={clock}
            onRigLabel={(v) => {
              setRigLabel(v);
              api.setConfig("rig_label", v);
            }}
          />
        )}
      </div>
      <TxBar
        txState={txState}
        decoding={decoding}
        onStop={() => api.stopTx()}
      />
      <div className="status-line">{status}</div>
    </>
  );
}

function TopBar(props: {
  utcMs: number | null;
  decoding: boolean;
  onToggleDecode: () => void;
  bands: BandInfo[];
  dialHz: number;
  onBand: (hz: number) => void;
  rig: RigStatusEvent | null;
  clock: ClockSyncEvent | null;
  audio: { db: number; silent: boolean } | null;
  rigLabel: string;
  txGain: number;
  onTxGain: (v: number) => void;
}) {
  const { utcMs, decoding, onToggleDecode, bands, dialHz, onBand, rig, clock, audio, rigLabel, txGain, onTxGain } = props;
  const utc = utcMs ? new Date(utcMs).toISOString().substring(11, 19) : "--:--:--";
  return (
    <div className="topbar">
      <span className="brand">RTTYAF</span>
      <span className="clock">{utc}</span>
      <ClockSyncBadge clock={clock} />
      <span className="muted">Continuous Baudot · internal testing</span>
      <div className="spacer" />
      <span className="tx-level" title="TX output level (drive) — lower until ALC stops pinning">
        <span className="muted">TX</span>
        <input
          type="range"
          aria-label="TX output level (drive), percent"
          min={0}
          max={100}
          step={1}
          value={txGain}
          onChange={(e) => onTxGain(parseInt(e.target.value, 10))}
          style={{ width: 90 }}
        />
        <span className="muted" style={{ fontVariantNumeric: "tabular-nums", minWidth: 34, textAlign: "right" }}>
          {txGain}%
        </span>
      </span>
      <select value={dialHz} onChange={(e) => onBand(parseInt(e.target.value, 10))}>
        {!bands.some((b) => b.dial_hz === dialHz) && <option value={dialHz}>Custom · {(dialHz / 1e6).toFixed(6)}</option>}
        {bands.map((b) => (
          <option key={b.name} value={b.dial_hz}>
            {b.name} · {(b.dial_hz / 1e6).toFixed(3)}
          </option>
        ))}
      </select>
      <input aria-label="Dial frequency Hz" type="number" min={1} step={100} value={dialHz}
        style={{ width: 110 }} onChange={(e) => { const hz = Number(e.target.value); if (hz > 0) onBand(hz); }} />
      <span className="muted">
        {rig?.connected ? `rig: ${rigLabel || rig.model}` : "no rig"}
        {rig?.ptt ? " · PTT" : ""}
      </span>
      <AudioBadge decoding={decoding} audio={audio} />
      <button className={decoding ? "danger" : "primary"} onClick={onToggleDecode}>
        {decoding ? "Stop" : "Start"} decode
      </button>
    </div>
  );
}

// RX input-level indicator. Shows the captured level in dBFS while decoding, and
// a clear "no audio" warning when the input is silent — the case where the source
// (e.g. a DAX/soundcard channel) isn't actually routed and the waterfall is blank.
function AudioBadge({
  decoding,
  audio,
}: {
  decoding: boolean;
  audio: { db: number; silent: boolean } | null;
}) {
  if (!decoding) return null;
  if (!audio) {
    return <span className="muted" title="Waiting for the first input-level reading">audio …</span>;
  }
  if (audio.silent) {
    return (
      <span
        style={{ color: "var(--tx)", fontVariantNumeric: "tabular-nums" }}
        title="No audio is reaching the app. Check that the selected input device is the right one and that its source is routed (e.g. SmartSDR slice → DAX RX channel, soundcard input not muted)."
      >
        ⚠ no audio
      </span>
    );
  }
  return (
    <span
      className="muted"
      style={{ fontVariantNumeric: "tabular-nums" }}
      title="RX input level (dBFS) of the captured audio"
    >
      audio {audio.db.toFixed(0)} dB
    </span>
  );
}

// Format an NTP offset (ms) as a signed seconds string, e.g. "+0.12s".
function fmtOffset(ms: number): string {
  const s = ms / 1000;
  return `${s >= 0 ? "+" : ""}${s.toFixed(2)}s`;
}

// Clock sync affects log timestamps only; asynchronous RTTY has no UTC slots.
function ClockSyncBadge({ clock }: { clock: ClockSyncEvent | null }) {
  if (!clock || !clock.synced) {
    return <span className="muted" title="Waiting for NTP time sync">sync …</span>;
  }
  return (
    <span
      className="muted"
      title={`Log clock offset from NTP: ${clock.offset_ms} ms; RTTY does not require slot synchronization`}
    >
      sync {fmtOffset(clock.offset_ms)}
    </span>
  );
}

function WaterfallView({
  modem,
  onSelectFreq,
}: {
  modem: RttyConfig;
  onSelectFreq: (hz: number) => void;
}) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    registerWfCanvas(ref.current);
    return () => registerWfCanvas(null);
  }, []);

  function handleClick(e: MouseEvent<HTMLDivElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    const hz = Math.round(frac * WF_TOP_HZ);
    onSelectFreq(Math.min(3000 - modem.shift_hz, Math.max(500, hz)));
  }

  const mark = modem.mark_hz + (modem.reverse ? modem.shift_hz : 0);
  const space = modem.mark_hz + (modem.reverse ? 0 : modem.shift_hz);
  const leftPct = (modem.mark_hz / WF_TOP_HZ) * 100;

  return (
    <div
      className="waterfall"
      style={{ position: "relative", marginBottom: 8, cursor: "crosshair" }}
      onClick={handleClick}
      title="Click to tune the RX/TX tone pair (lower audio tone)"
    >
      <canvas
        ref={ref}
        style={{
          width: "100%",
          height: WF_HEIGHT,
          imageRendering: "pixelated",
          background: "#000",
          borderRadius: 6,
          display: "block",
        }}
      />
      {/* MARK and SPACE remain distinct when polarity is reversed. */}
      <div style={{ position: "absolute", top: 0, bottom: 0, left: `${space / WF_TOP_HZ * 100}%`,
        width: 2, background: "#4ea1ff", pointerEvents: "none" }} />
      <div
        style={{
          position: "absolute",
          top: 0,
          bottom: 0,
          left: `${mark / WF_TOP_HZ * 100}%`,
          width: 2,
          background: "#ff3b30",
          pointerEvents: "none",
        }}
      />
      <div
        style={{
          position: "absolute",
          top: 2,
          left: `clamp(4px, calc(${leftPct}% + 4px), calc(100% - 170px))`,
          background: "rgba(0,0,0,0.85)",
          padding: "2px 4px",
          color: "#ff8f87",
          fontSize: 11,
          fontVariantNumeric: "tabular-nums",
          pointerEvents: "none",
          textShadow: "0 0 3px #000",
        }}
      >
        M {mark} / S {space} Hz
      </div>
    </div>
  );
}

// Map a 0..255 magnitude to a dark-blue -> cyan -> yellow -> white ramp.
function colormap(v: number): [number, number, number] {
  const t = v / 255;
  if (t < 0.25) return [0, 0, Math.round(60 + t * 4 * 140)];
  if (t < 0.5) return [0, Math.round((t - 0.25) * 4 * 200), 200];
  if (t < 0.75) return [Math.round((t - 0.5) * 4 * 230), 230, Math.round(200 - (t - 0.5) * 4 * 200)];
  return [255, Math.round(230 + (t - 0.75) * 4 * 25), Math.round((t - 0.75) * 4 * 200)];
}

function DecodeScreen(props: {
  received: string;
  onClear: () => void;
  modem: RttyConfig;
  onModem: (config: RttyConfig) => void;
}) {
  const { received, onClear, modem, onModem } = props;
  const rx = useRef<HTMLPreElement>(null);
  useEffect(() => { if (rx.current) rx.current.scrollTop = rx.current.scrollHeight; }, [received]);
  return (
    <>
      <WaterfallView modem={modem} onSelectFreq={(mark_hz) => onModem({ ...modem, mark_hz })} />
      <div className="row modem-controls">
        <label>Baud <select aria-label="RTTY baud" value={modem.baud_rate} onChange={(e) => onModem({ ...modem, baud_rate: Number(e.target.value) })}>
          {[45.45, 50, 75].map((n) => <option key={n}>{n}</option>)}
        </select></label>
        <label>Shift <select aria-label="RTTY shift Hz" value={modem.shift_hz} onChange={(e) => {
          const shift_hz = Number(e.target.value); onModem({ ...modem, shift_hz, mark_hz: Math.min(modem.mark_hz, 3000 - shift_hz) });
        }}>{[170, 200, 425, 850].map((n) => <option key={n}>{n}</option>)}</select> Hz</label>
        <label>Lower tone <input aria-label="Lower tone Hz" type="number" min={500} max={3000 - modem.shift_hz} value={modem.mark_hz}
          onChange={(e) => { const mark_hz = Number(e.target.value); if (mark_hz >= 500 && mark_hz + modem.shift_hz <= 3000) onModem({ ...modem, mark_hz }); }} /> Hz</label>
        <label>Stop <select aria-label="Stop bits" value={modem.stop_bits} onChange={(e) => onModem({ ...modem, stop_bits: Number(e.target.value) })}>
          {[1, 1.5, 2].map((n) => <option key={n}>{n}</option>)}
        </select></label>
        <label><input type="checkbox" checked={modem.reverse} onChange={(e) => onModem({ ...modem, reverse: e.target.checked })} /> Reverse (USB)</label>
        <label><input type="checkbox" checked={modem.unshift_on_space} onChange={(e) => onModem({ ...modem, unshift_on_space: e.target.checked })} /> USOS</label>
        <label><input type="checkbox" checked={modem.squelch} onChange={(e) => onModem({ ...modem, squelch: e.target.checked })} /> Squelch</label>
        <button onClick={onClear}>Clear RX</button>
      </div>
      <p className="muted">Set the rig to LSB, or USB with Reverse. Check local band permissions. Manual overs only; changing tones or dial stops TX.</p>
      <pre className="rx-text" ref={rx} aria-label="Received RTTY text">{received || "Choose an audio input in Settings, then Start decode. Text streams here continuously."}</pre>
    </>
  );
}

function LogScreen({ dialHz, utcMs, bands }: { dialHz: number; utcMs: number | null; bands: BandInfo[] }) {
  const [rows, setRows] = useState<QsoRecord[]>([]);
  const [count, setCount] = useState(0);
  const [call, setCall] = useState("");
  const [sent, setSent] = useState("599");
  const [rcvd, setRcvd] = useState("599");
  const [logStatus, setLogStatus] = useState("");

  function refresh() {
    api.listLog(500, 0).then(setRows);
    api.logCount().then(setCount);
  }
  useEffect(refresh, []);

  async function exportAdif() {
    const adif = await api.exportAdif();
    const blob = new Blob([adif], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "rttyaf_log.adi";
    a.click();
    URL.revokeObjectURL(url);
  }

  async function remove(id?: number | null) {
    if (id == null) return;
    await api.deleteQso(id);
    refresh();
  }

  return (
    <>
      <form className="row modem-controls panel" onSubmit={async (e) => {
        e.preventDefault();
        const now = new Date(utcMs ?? Date.now()).toISOString();
        const date = now.slice(0, 10).replace(/-/g, "");
        const time = now.slice(11, 19).replace(/:/g, "");
        const config = Object.fromEntries(await api.allConfig());
        try {
          await api.saveQso({ call: call.trim().toUpperCase(), gridsquare: "", mode: "RTTY",
            rst_sent: sent, rst_rcvd: rcvd, qso_date: date, time_on: time,
            qso_date_off: date, time_off: time, band: bands.find((b) => dialHz >= b.lo_hz && dialHz <= b.hi_hz)?.name ?? "", freq: (dialHz / 1e6).toFixed(6),
            station_callsign: config.my_call ?? "", my_gridsquare: config.my_grid ?? "", comment: "" });
          setLogStatus(`Logged ${call.toUpperCase()} · RTTY`); setCall(""); refresh();
        } catch (error) { setLogStatus(String(error)); }
      }}>
        <label>Call <input aria-label="Contact callsign" required value={call} onChange={(e) => setCall(e.target.value.toUpperCase())} /></label>
        <label>RST sent <input aria-label="RST sent" required value={sent} onChange={(e) => setSent(e.target.value)} /></label>
        <label>RST received <input aria-label="RST received" required value={rcvd} onChange={(e) => setRcvd(e.target.value)} /></label>
        <button className="primary" type="submit">Log RTTY QSO now</button>
        <span role="status">{logStatus}</span>
      </form>
      <div className="row" style={{ marginBottom: 10 }}>
        <strong>{count}</strong> <span className="muted">QSOs</span>
        <div className="spacer" style={{ flex: 1 }} />
        <button onClick={refresh}>Refresh</button>
        <button className="primary" onClick={exportAdif}>Export ADIF</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Time</th>
            <th>Call</th>
            <th>Grid</th>
            <th>Band</th>
            <th>Sent</th>
            <th>Rcvd</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{r.qso_date}</td>
              <td>{r.time_on}</td>
              <td>{r.call}</td>
              <td>{r.gridsquare}</td>
              <td>{r.band}</td>
              <td>{r.rst_sent}</td>
              <td>{r.rst_rcvd}</td>
              <td>
                <button onClick={() => remove(r.id)}>✕</button>
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={8} className="muted">No QSOs logged yet.</td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  );
}

// Waterfall FFT developer-knob option lists (issue #428). Single source of
// truth for both the <select> options and validation of persisted config —
// raw config strings are untrusted (hand-edited DB, older builds), so
// anything outside these lists falls back to the defaults.
const WF_WINDOWS: WfWindow[] = ["rect", "hann", "hamming", "blackman", "blackman_harris"];
const WF_WINDOW_LABELS: Record<WfWindow, string> = {
  rect: "Rectangular (none)",
  hann: "Hann (default)",
  hamming: "Hamming",
  blackman: "Blackman",
  blackman_harris: "Blackman-Harris",
};
const WF_FFT_SIZES = [512, 1024, 2048, 4096, 8192];
const WF_AVG_OPTIONS = [1, 2, 3, 4, 6, 8, 12, 16];

function SettingsScreen(props: {
  onStatus: (s: string) => void;
  clock: ClockSyncEvent | null;
  onRigLabel: (v: string) => void;
}) {
  const { onStatus, clock, onRigLabel } = props;
  const [call, setCall] = useState("");
  const [rigLabel, setRigLabel] = useState("");
  const [grid, setGrid] = useState("");
  const [inputs, setInputs] = useState<AudioDevice[]>([]);
  const [outputs, setOutputs] = useState<AudioDevice[]>([]);
  const [ports, setPorts] = useState<SerialPortInfo[]>([]);
  const [hamlibRigs, setHamlibRigs] = useState<HamlibRig[]>([]);
  const [input, setInput] = useState("");
  const [output, setOutput] = useState("");
  // Live-waterfall FFT developer knobs (issue #428). Defaults mirror
  // WfConfig::default() on the Rust side: hann / 2048 / 6.
  const [wfCfg, setWfCfg] = useState<WaterfallConfig>({
    window: "hann",
    fft_size: 2048,
    avg: 6,
  });
  const [rigCfg, setRigCfg] = useState<RigConfig>({
    backend: "hamlib",
    model: "none",
    port: "",
    baud: 38400,
    ptt: "cat",
    civ_address: 0x94,
    flrig_host: "127.0.0.1",
    flrig_port: 12345,
    hamlib_model: 1,
    hamlib_network: false,
    hamlib_address: "127.0.0.1:4992",
  });

  useEffect(() => {
    api.listAudioInputs().then(setInputs);
    api.listAudioOutputs().then(setOutputs);
    api.listSerialPorts().then(setPorts);
    api.listHamlibRigs().then(setHamlibRigs);
    api.getConfig("my_call").then((v) => v && setCall(v));
    api.getConfig("my_grid").then((v) => v && setGrid(v));
    api.getConfig("input_device").then((v) => v && setInput(v));
    api.getConfig("output_device").then((v) => v && setOutput(v));
    api.getConfig("rig_config").then((v) => {
      if (v) {
        try {
          setRigCfg(JSON.parse(v));
        } catch {
          /* ignore malformed saved config */
        }
      }
    });
    api.getConfig("rig_label").then((v) => v && setRigLabel(v));
    // Restore persisted waterfall knobs (written by the engine on change),
    // keeping only values from the option lists — an unknown window string or
    // NaN size would leave the <select>s blank and push an invalid config
    // back through applyWfCfg.
    Promise.all([
      api.getConfig("wf_window"),
      api.getConfig("wf_fft_size"),
      api.getConfig("wf_avg"),
    ]).then(([w, size, avg]) => {
      const fftSize = parseInt(size ?? "", 10);
      const avgN = parseInt(avg ?? "", 10);
      setWfCfg((prev) => ({
        window: WF_WINDOWS.includes(w as WfWindow) ? (w as WfWindow) : prev.window,
        fft_size: WF_FFT_SIZES.includes(fftSize) ? fftSize : prev.fft_size,
        avg: WF_AVG_OPTIONS.includes(avgN) ? avgN : prev.avg,
      }));
    });
  }, []);

  function applyWfCfg(next: WaterfallConfig) {
    setWfCfg(next);
    api.setWaterfallConfig(next);
  }

  const refreshPorts = () => api.listSerialPorts().then(setPorts);

  // Re-enumerate serial ports whenever a port-list backend becomes visible.
  // Ports were previously read only once at mount, so a rig attached after
  // launch — or Hamlib selected first, before its serial port was chosen —
  // showed an empty/stale list. Switching to Hamlib (serial) or Direct serial
  // now re-scans, so /dev/ttyACM0 (a QMX/QDX) appears without the
  // select-Direct-then-Hamlib workaround.
  const showsPortList =
    rigCfg.backend === "serial" ||
    (rigCfg.backend === "hamlib" && !rigCfg.hamlib_network);
  useEffect(() => {
    if (showsPortList) refreshPorts();
  }, [showsPortList]);

  function saveStation() {
    api.setStation(call, grid);
    onStatus(`Station set: ${call} ${grid}`);
  }

  return (
    <div className="grid2">
      <div className="panel">
        <h3>Station</h3>
        <div className="col">
          <div className="field">
            <label>Callsign</label>
            <input value={call} onChange={(e) => setCall(e.target.value.toUpperCase())} placeholder="K0XYZ" />
          </div>
          <div className="field">
            <label>Grid (Maidenhead)</label>
            <input value={grid} onChange={(e) => setGrid(e.target.value.toUpperCase())} placeholder="EN37" />
          </div>
          <button className="primary" onClick={saveStation}>Save station</button>
        </div>
      </div>

      <div className="panel">
        <h3>Time sync</h3>
        <div className="col">
          <div className="muted">
            RTTY is asynchronous: there are no UTC slots or DT calibration.
            NTP corrects the app's log clock only; the system clock is untouched.
          </div>
          <div className="field">
            <label>Clock offset from UTC</label>
            <div>
              {clock?.synced
                ? `${fmtOffset(clock.offset_ms)} (${clock.offset_ms} ms)`
                : "waiting for first sync…"}
            </div>
          </div>
          <button
            onClick={() => {
              api.resyncTime();
              onStatus("Re-syncing clock to NTP…");
            }}
          >
            Sync now
          </button>
        </div>
      </div>

      <div className="panel">
        <h3>Audio</h3>
        <div className="col">
          <div className="field">
            <label>Input device (RX)</label>
            <select
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                api.setInputDevice(e.target.value || null);
              }}
            >
              <option value="">System default</option>
              {inputs.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} {d.is_default ? "(default)" : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Output device (TX)</label>
            <select
              value={output}
              onChange={(e) => {
                setOutput(e.target.value);
                api.setOutputDevice(e.target.value || null);
              }}
            >
              <option value="">System default</option>
              {outputs.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} {d.is_default ? "(default)" : ""}
                </option>
              ))}
            </select>
          </div>
          <p className="muted">RX and TX share the RTTY tone pair on the Decode tab. With no CAT/PTT backend, keying is manual/VOX.</p>
        </div>
      </div>

      <div className="panel">
        <h3>Rig control</h3>
        <div className="col">
          <div className="field">
            <label>Display name (optional)</label>
            <input
              value={rigLabel}
              onChange={(e) => {
                setRigLabel(e.target.value);
                onRigLabel(e.target.value);
              }}
              placeholder="e.g. Flex 6400"
            />
            <span className="muted" style={{ display: "block", marginTop: 4 }}>
              Shown in the top bar instead of the CAT model. Useful when connecting
              a radio through an emulator (e.g. a Flex via SmartSDR CAT reports as
              Kenwood TS-2000). Leave blank to show the detected model.
            </span>
          </div>
          <div className="field">
            <label>Backend</label>
            <select
              value={rigCfg.backend}
              onChange={(e) => setRigCfg({ ...rigCfg, backend: e.target.value as RigConfig["backend"] })}
            >
              <option value="hamlib">Hamlib (embedded library)</option>
              <option value="flrig">FLrig (XML-RPC)</option>
              <option value="serial">Direct serial CAT</option>
              <option value="none">None (manual tuning)</option>
            </select>
          </div>

          {rigCfg.backend === "hamlib" && (
            <>
              <div className="field">
                <label>Radio {hamlibRigs.length > 0 ? `(${hamlibRigs.length} supported)` : ""}</label>
                {hamlibRigs.length > 0 ? (
                  <select
                    value={rigCfg.hamlib_model}
                    onChange={(e) =>
                      setRigCfg({ ...rigCfg, hamlib_model: parseInt(e.target.value, 10) })
                    }
                  >
                    {hamlibRigs.map((r) => (
                      <option key={r.model} value={r.model}>
                        {r.name} — #{r.model}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    value={rigCfg.hamlib_model}
                    onChange={(e) =>
                      setRigCfg({ ...rigCfg, hamlib_model: parseInt(e.target.value, 10) || 1 })
                    }
                    placeholder="model # (rigctl -l)"
                    style={{ width: 120 }}
                  />
                )}
              </div>
              <div className="field">
                <label>Connection</label>
                <select
                  value={rigCfg.hamlib_network ? "network" : "serial"}
                  onChange={(e) => setRigCfg({ ...rigCfg, hamlib_network: e.target.value === "network" })}
                >
                  <option value="serial">Serial port (COM)</option>
                  <option value="network">Network (TCP host:port)</option>
                </select>
              </div>

              {rigCfg.hamlib_network ? (
                <div className="field">
                  <label>Network address (host:port)</label>
                  <input
                    value={rigCfg.hamlib_address}
                    onChange={(e) => setRigCfg({ ...rigCfg, hamlib_address: e.target.value })}
                    placeholder="127.0.0.1:4992"
                    style={{ width: 180 }}
                  />
                  <span className="muted" style={{ display: "block", marginTop: 4 }}>
                    Native FlexRadio 6xxx model: <code>&lt;radio-IP&gt;:4992</code>. For
                    SmartSDR CAT's TCP port, use model <b>Kenwood TS-2000</b> +{" "}
                    <code>127.0.0.1:&lt;cat-port&gt;</code> (e.g. 5002). Use 127.0.0.1, not
                    localhost.
                  </span>
                </div>
              ) : (
                <div className="row">
                  <div className="field">
                    <label>Serial port</label>
                    <div className="row">
                      <select value={rigCfg.port} onChange={(e) => setRigCfg({ ...rigCfg, port: e.target.value })}>
                        <option value="">(none — e.g. Dummy)</option>
                        {ports.map((p) => (
                          <option key={p.name} value={p.name}>
                            {p.name} ({p.kind})
                          </option>
                        ))}
                      </select>
                      <button type="button" title="Re-scan serial ports" onClick={refreshPorts}>
                        ↻
                      </button>
                    </div>
                  </div>
                  <div className="field">
                    <label>Baud</label>
                    <select
                      value={rigCfg.baud}
                      onChange={(e) => setRigCfg({ ...rigCfg, baud: parseInt(e.target.value, 10) })}
                    >
                      {[4800, 9600, 19200, 38400, 57600, 115200].map((b) => (
                        <option key={b} value={b}>{b}</option>
                      ))}
                    </select>
                  </div>
                </div>
              )}
              {hamlibRigs.length === 0 && (
                <span className="muted">
                  Hamlib library not found — install Hamlib and put <code>hamlib-4.dll</code> next
                  to the app or on PATH, then reopen. (1 = Dummy, no hardware.)
                </span>
              )}
            </>
          )}

          {rigCfg.backend === "flrig" && (
            <div className="row">
              <div className="field">
                <label>FLrig host</label>
                <input
                  value={rigCfg.flrig_host}
                  onChange={(e) => setRigCfg({ ...rigCfg, flrig_host: e.target.value })}
                  style={{ width: 130 }}
                />
              </div>
              <div className="field">
                <label>Port</label>
                <input
                  value={rigCfg.flrig_port}
                  onChange={(e) =>
                    setRigCfg({ ...rigCfg, flrig_port: parseInt(e.target.value, 10) || 12345 })
                  }
                  style={{ width: 80 }}
                />
              </div>
            </div>
          )}

          {rigCfg.backend === "serial" && (
            <>
              <div className="field">
                <label>Model</label>
                <select
                  value={rigCfg.model}
                  onChange={(e) => setRigCfg({ ...rigCfg, model: e.target.value as RigConfig["model"] })}
                >
                  <option value="none">— select —</option>
                  <option value="yaesu">Yaesu (CAT)</option>
                  <option value="kenwood">Kenwood</option>
                  <option value="icom">Icom (CI-V)</option>
                </select>
              </div>
              <div className="field">
                <label>Serial port</label>
                <div className="row">
                  <select value={rigCfg.port} onChange={(e) => setRigCfg({ ...rigCfg, port: e.target.value })}>
                    <option value="">— select —</option>
                    {ports.map((p) => (
                      <option key={p.name} value={p.name}>
                        {p.name} ({p.kind})
                      </option>
                    ))}
                  </select>
                  <button type="button" title="Re-scan serial ports" onClick={refreshPorts}>
                    ↻
                  </button>
                </div>
              </div>
              <div className="row">
                <div className="field">
                  <label>Baud</label>
                  <select
                    value={rigCfg.baud}
                    onChange={(e) => setRigCfg({ ...rigCfg, baud: parseInt(e.target.value, 10) })}
                  >
                    {[4800, 9600, 19200, 38400, 57600, 115200].map((b) => (
                      <option key={b} value={b}>{b}</option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>PTT</label>
                  <select
                    value={rigCfg.ptt}
                    onChange={(e) => setRigCfg({ ...rigCfg, ptt: e.target.value as RigConfig["ptt"] })}
                  >
                    <option value="cat">CAT</option>
                    <option value="rts">RTS</option>
                    <option value="dtr">DTR</option>
                    <option value="none">None / VOX</option>
                  </select>
                </div>
                {rigCfg.model === "icom" && (
                  <div className="field">
                    <label>CI-V addr (hex)</label>
                    <input
                      value={rigCfg.civ_address.toString(16)}
                      onChange={(e) =>
                        setRigCfg({ ...rigCfg, civ_address: parseInt(e.target.value, 16) || 0x94 })
                      }
                      style={{ width: 60 }}
                    />
                  </div>
                )}
              </div>
            </>
          )}

          <button
            className="primary"
            onClick={() => {
              api.selectRig(rigCfg);
              const where =
                rigCfg.backend === "flrig"
                  ? `${rigCfg.flrig_host}:${rigCfg.flrig_port}`
                  : rigCfg.backend === "serial"
                  ? `${rigCfg.model} on ${rigCfg.port || "(no port)"}`
                  : rigCfg.backend === "hamlib"
                  ? `model ${rigCfg.hamlib_model} on ${
                      rigCfg.hamlib_network ? rigCfg.hamlib_address : rigCfg.port || "(no port)"
                    }`
                  : "manual";
              onStatus(`Connecting rig (${rigCfg.backend}): ${where}`);
            }}
          >
            Connect rig
          </button>
          <button
            onClick={() => {
              api.disconnectRig();
              onStatus("Rig disconnected");
            }}
          >
            Disconnect
          </button>
        </div>
      </div>

      <div className="panel">
        <h3>Developer — waterfall FFT</h3>
        <div className="col">
          <div className="muted">
            Spectrum/waterfall rendering experiments (issue #428) — affects the
            display only, never decoding. Defaults: Hann, 2048, 6 averages.
          </div>
          <div className="field">
            <label>Window function</label>
            <select
              value={wfCfg.window}
              onChange={(e) => applyWfCfg({ ...wfCfg, window: e.target.value as WfWindow })}
            >
              {WF_WINDOWS.map((w) => (
                <option key={w} value={w}>
                  {WF_WINDOW_LABELS[w]}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>FFT size</label>
            <select
              value={wfCfg.fft_size}
              onChange={(e) => applyWfCfg({ ...wfCfg, fft_size: parseInt(e.target.value, 10) })}
            >
              {WF_FFT_SIZES.map((n) => (
                <option key={n} value={n}>
                  {n} — {(12000 / n).toFixed(1)} Hz/bin{n === 2048 ? " (default)" : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Averaging (Welch segments per row)</label>
            <select
              value={wfCfg.avg}
              onChange={(e) => applyWfCfg({ ...wfCfg, avg: parseInt(e.target.value, 10) })}
            >
              {WF_AVG_OPTIONS.map((n) => (
                <option key={n} value={n}>
                  {n}{n === 6 ? " (default)" : n === 1 ? " (off)" : ""}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>
    </div>
  );
}

function TxBar(props: {
  txState: TxStateEvent | null;
  decoding: boolean;
  onStop: () => void;
}) {
  const { txState, decoding, onStop } = props;
  const [text, setText] = useState("");
  const tx = txState?.transmitting;
  return (
    <div className="txbar">
      <span className={"badge " + (tx ? "tx" : "rx")}>{tx ? "TX" : "RX"}</span>
      <div className="col" style={{ flex: 1 }}>
        <span className="muted">{tx ? "Sending one over" : decoding ? "Listening · manual TX" : "Receiver stopped · manual TX"}</span>
        <textarea aria-label="Transmit RTTY text" maxLength={1000} rows={2} disabled={tx}
          value={text} onChange={(e) => setText(e.target.value)} placeholder="Type an RTTY over, then Send once. Unsupported characters are skipped." />
        {tx && <span className="msg">{txState?.message}</span>}
      </div>
      <button disabled={tx} onClick={async () => {
        const call = await api.getConfig("my_call");
        if (call) setText(`CQ CQ DE ${call} ${call} K`);
        else window.alert("Set your callsign in Settings first");
      }}>Prepare CQ</button>
      <button className="primary" disabled={tx || !text.trim()} onClick={() => api.freeText(text)}>Send once</button>
      <button className="danger" onClick={onStop}>Stop TX</button>
    </div>
  );
}
