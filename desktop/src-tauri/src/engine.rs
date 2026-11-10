//! Continuous RTTY receiver and manually initiated, single-over transmitter.
//! Audio streams and rig stay on this thread (cpal is !Send on Windows).

use serde::Serialize;
use std::sync::mpsc::{Receiver, Sender, TryRecvError};
use std::sync::Arc;
use std::time::Duration;

use crate::audio::{output, AudioInput, CaptureBuffer};
use crate::bands;
use crate::db::Db;
use crate::dsp::{self, Decoder, RttyConfig, SAMPLE_RATE};
use crate::rig::{RigConfig, RigConnection};
use crate::util;
use crate::wf::{WfConfig, WfProcessor};

const PTT_DELAY_MS: u64 = 100;
const SILENCE_DBFS: f32 = -75.0;

fn rms_dbfs(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return -120.0;
    }
    let power: f64 = samples.iter().map(|&s| (s as f64).powi(2)).sum();
    (10.0 * (power / samples.len() as f64).max(1e-12).log10()) as f32
}

fn clamp_tx_gain(g: f32) -> f32 {
    if g.is_finite() {
        g.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

fn validate_tx(text: &str, config: RttyConfig) -> Result<String, String> {
    if text.chars().count() > 1000 {
        return Err("An RTTY over is limited to 1000 characters".into());
    }
    if dsp::text_to_codes(text.trim(), config).len() <= 1 {
        return Err("Enter text supported by US-TTY Baudot before transmitting".into());
    }
    Ok(dsp::tx_message(text))
}

#[derive(Debug, Clone)]
pub enum EngineCommand {
    StartDecode,
    StopDecode,
    SetStation { call: String, grid: String },
    SetBand(u64),
    SetModem(RttyConfig),
    SetTxGain(f32),
    SetInputDevice(Option<String>),
    SetOutputDevice(Option<String>),
    SelectRig(RigConfig),
    DisconnectRig,
    StopTx,
    FreeText(String),
    RefreshStatus,
    SetClockOffset(i64),
    ResyncTime,
    SetWaterfallConfig(WfConfig),
    Shutdown,
}

#[derive(Debug, Clone, Serialize)]
pub struct TxStateEvent {
    pub transmitting: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct RigStatusEvent {
    pub connected: bool,
    pub model: String,
    pub frequency_hz: u64,
    pub ptt: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct ClockSyncEvent {
    pub offset_ms: i64,
    pub synced: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct WaterfallRow {
    pub bins: Vec<u8>,
    pub hz_per_col: f32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data", rename_all = "snake_case")]
pub enum EngineEvent {
    Received(String),
    Clock(i64),
    ClockSync(ClockSyncEvent),
    TxState(TxStateEvent),
    RigStatus(RigStatusEvent),
    Modem(RttyConfig),
    Decoding(bool),
    WaterfallRow(WaterfallRow),
    InputLevel(InputLevelEvent),
    Info(String),
    Error(String),
}

#[derive(Debug, Clone, Serialize)]
pub struct InputLevelEvent {
    pub db: f32,
    pub silent: bool,
}

pub struct EngineHandle {
    tx: parking_lot::Mutex<Sender<EngineCommand>>,
    worker: parking_lot::Mutex<Option<std::thread::JoinHandle<()>>>,
}
impl EngineHandle {
    pub fn send(&self, cmd: EngineCommand) {
        let _ = self.tx.lock().send(cmd);
    }
    /// Wait for audio shutdown and the PTT-OFF attempt before the app exits.
    pub fn shutdown(&self) {
        self.send(EngineCommand::Shutdown);
        if let Some(worker) = self.worker.lock().take() {
            let _ = worker.join();
        }
    }
}

pub fn spawn(db: Arc<Db>) -> (EngineHandle, Receiver<EngineEvent>) {
    let (cmd_tx, cmd_rx) = std::sync::mpsc::channel();
    let (evt_tx, evt_rx) = std::sync::mpsc::channel();
    crate::timesync::spawn_loop(cmd_tx.clone());
    let sync_tx = cmd_tx.clone();
    let worker = std::thread::Builder::new()
        .name("rttyaf-engine".into())
        .spawn(move || {
            let mut engine = Engine::new(db, evt_tx, sync_tx);
            engine.run(cmd_rx);
        })
        .expect("spawn engine thread");
    (
        EngineHandle {
            tx: parking_lot::Mutex::new(cmd_tx),
            worker: parking_lot::Mutex::new(Some(worker)),
        },
        evt_rx,
    )
}

struct Engine {
    db: Arc<Db>,
    evt: Sender<EngineEvent>,
    cmd_tx: Sender<EngineCommand>,
    accum: Arc<CaptureBuffer>,
    decoder: Decoder,
    config: RttyConfig,
    rig: Option<RigConnection>,
    input: Option<AudioInput>,
    input_device: Option<String>,
    output_device: Option<String>,
    dial_hz: u64,
    tx_gain: f32,
    ptt: bool,
    tx_playback: Option<output::PreparedTx>,
    tx_message: Option<String>,
    clock_offset_ms: i64,
    time_synced: bool,
    wf: WfProcessor,
}

impl Engine {
    fn new(db: Arc<Db>, evt: Sender<EngineEvent>, cmd_tx: Sender<EngineCommand>) -> Self {
        let config = db
            .get_config("rtty_modem")
            .and_then(|s| serde_json::from_str::<RttyConfig>(&s).ok())
            .filter(|c| c.validate().is_ok() && c.sample_rate == SAMPLE_RATE)
            .unwrap_or_default();
        let dial_hz = db
            .get_config("dial_hz")
            .and_then(|s| s.parse().ok())
            .unwrap_or(bands::default_band().dial_hz);
        let tx_gain = db
            .get_config("tx_gain")
            .and_then(|s| s.parse().ok())
            .map(clamp_tx_gain)
            .unwrap_or(0.9);
        let input_device = db.get_config("input_device").filter(|s| !s.is_empty());
        let output_device = db.get_config("output_device").filter(|s| !s.is_empty());
        let offset = db
            .get_config("clock_offset_ms")
            .and_then(|s| s.parse().ok());
        let wf_config = db
            .get_config("waterfall")
            .and_then(|s| serde_json::from_str::<WfConfig>(&s).ok())
            .unwrap_or_default();
        Self {
            db,
            evt,
            cmd_tx,
            accum: Arc::new(CaptureBuffer::new()),
            decoder: Decoder::new(config).expect("validated modem"),
            config,
            rig: None,
            input: None,
            input_device,
            output_device,
            dial_hz,
            tx_gain,
            ptt: false,
            tx_playback: None,
            tx_message: None,
            clock_offset_ms: offset.unwrap_or(0),
            time_synced: offset.is_some(),
            wf: WfProcessor::new(wf_config.sanitize()),
        }
    }

    fn now(&self) -> i64 {
        util::now_unix_ms() + self.clock_offset_ms
    }

    fn run(&mut self, commands: Receiver<EngineCommand>) {
        if let Some(cfg) = self
            .db
            .get_config("rig_config")
            .and_then(|s| serde_json::from_str::<RigConfig>(&s).ok())
        {
            if cfg.backend != crate::rig::RigBackend::None {
                self.select_rig(cfg);
            }
        }
        let mut last_tick = 0;
        loop {
            loop {
                match commands.try_recv() {
                    Ok(EngineCommand::Shutdown) | Err(TryRecvError::Disconnected) => {
                        self.finalize_tx();
                        return;
                    }
                    Ok(cmd) => self.handle(cmd),
                    Err(TryRecvError::Empty) => break,
                }
            }
            if self
                .tx_playback
                .as_ref()
                .is_some_and(|p| p.is_done() || p.timed_out())
            {
                self.finalize_tx();
            }
            if self.input.is_some() {
                let (samples, overflow) = self.accum.drain();
                if overflow {
                    self.reset_decoder();
                    self.emit(EngineEvent::Error(
                        "RX buffer overflow; partial RTTY frame discarded".into(),
                    ));
                }
                if self.tx_playback.is_none() {
                    let text = self.decoder.process(&samples);
                    if !text.is_empty() {
                        self.emit(EngineEvent::Received(text));
                    }
                }
                if let Some((bins, hz_per_col)) = self.wf.process_row(
                    &self.accum.peek_recent(self.wf.samples_needed()),
                    SAMPLE_RATE as f32,
                ) {
                    self.emit(EngineEvent::WaterfallRow(WaterfallRow { bins, hz_per_col }));
                }
            }
            if self.now() - last_tick >= 1000 {
                last_tick = self.now();
                self.emit(EngineEvent::Clock(last_tick));
                if self.input.is_some() {
                    let db = rms_dbfs(&self.accum.peek_recent(SAMPLE_RATE as usize / 2));
                    self.emit(EngineEvent::InputLevel(InputLevelEvent {
                        db,
                        silent: db <= SILENCE_DBFS,
                    }));
                }
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    fn reset_decoder(&mut self) {
        self.decoder = Decoder::new(self.config).expect("validated modem");
    }

    fn start_transmit(&mut self, text: &str) {
        if self.tx_playback.is_some() || self.ptt {
            self.emit(EngineEvent::Error(
                "Stop the current over and confirm PTT is off before sending another".into(),
            ));
            return;
        }
        let message = match validate_tx(text, self.config) {
            Ok(m) => m,
            Err(e) => {
                self.emit(EngineEvent::Error(e));
                return;
            }
        };
        let signal = match dsp::encode(&message, self.config) {
            Ok(s) => s,
            Err(e) => {
                self.emit(EngineEvent::Error(e));
                return;
            }
        };
        // Open/resample BEFORE keying. No UTC slots or leading-audio clipping.
        let mut prepared =
            match output::prepare(self.output_device.as_deref(), &signal, self.tx_gain) {
                Ok(p) => p,
                Err(e) => {
                    self.emit(EngineEvent::Error(format!("playback failed: {e}")));
                    return;
                }
            };
        if !self.set_ptt(true) {
            self.set_ptt(false);
            return;
        }
        std::thread::sleep(Duration::from_millis(PTT_DELAY_MS));
        if let Err(e) = prepared.start(0) {
            self.emit(EngineEvent::Error(format!("playback failed: {e}")));
            self.set_ptt(false);
            return;
        }
        self.tx_message = Some(message);
        self.tx_playback = Some(prepared);
        self.publish_tx_state();
    }

    fn finalize_tx(&mut self) -> bool {
        self.tx_playback.take(); // halt audio before releasing the old rig
        if self.ptt {
            self.set_ptt(false);
        }
        self.tx_message = None;
        self.accum.clear();
        self.reset_decoder();
        self.publish_tx_state();
        !self.ptt
    }

    fn set_ptt(&mut self, on: bool) -> bool {
        // A failed key command may have reached the radio. Keep the possible
        // keyed state until OFF succeeds, so Stop retries and rig changes block.
        if on {
            self.ptt = true;
        }
        if let Some(rig) = self.rig.as_mut() {
            if let Err(e) = rig.set_ptt(on) {
                self.emit(EngineEvent::Error(format!(
                    "PTT failed: {e}; check the radio manually"
                )));
                self.publish_rig_status();
                return false;
            }
        }
        self.ptt = on;
        self.publish_rig_status();
        true
    }

    fn publish_tx_state(&self) {
        self.emit(EngineEvent::TxState(TxStateEvent {
            transmitting: self.tx_playback.is_some(),
            message: self.tx_message.clone(),
        }));
    }

    fn publish_rig_status(&self) {
        let (connected, model) = self
            .rig
            .as_ref()
            .map(|r| (r.connected(), r.name()))
            .unwrap_or((false, "None".into()));
        self.emit(EngineEvent::RigStatus(RigStatusEvent {
            connected,
            model,
            frequency_hz: self.dial_hz,
            ptt: self.ptt,
        }));
    }

    fn publish_clock_sync(&self) {
        self.emit(EngineEvent::ClockSync(ClockSyncEvent {
            offset_ms: self.clock_offset_ms,
            synced: self.time_synced,
        }));
    }

    fn emit(&self, event: EngineEvent) {
        let _ = self.evt.send(event);
    }

    fn handle(&mut self, cmd: EngineCommand) {
        match cmd {
            EngineCommand::StartDecode => self.start_decode(),
            EngineCommand::StopDecode => {
                self.input = None;
                self.accum.clear();
                self.reset_decoder();
                self.emit(EngineEvent::Decoding(false));
            }
            EngineCommand::SetStation { call, grid } => {
                let _ = self.db.set_config("my_call", &call.to_uppercase());
                let _ = self.db.set_config("my_grid", &grid.to_uppercase());
            }
            EngineCommand::SetBand(hz) => {
                if !self.finalize_tx() {
                    self.publish_rig_status();
                    return;
                }
                self.dial_hz = hz;
                let _ = self.db.set_config("dial_hz", &hz.to_string());
                if let Some(rig) = self.rig.as_mut() {
                    if let Err(e) = rig.set_frequency(hz) {
                        self.emit(EngineEvent::Error(format!("tuning failed: {e}")));
                    }
                }
                self.publish_rig_status();
            }
            EngineCommand::SetModem(config) => {
                if let Err(e) = config.validate().and_then(|_| {
                    if config.sample_rate == SAMPLE_RATE {
                        Ok(())
                    } else {
                        Err("Desktop audio requires 12000 Hz modem rate".into())
                    }
                }) {
                    self.emit(EngineEvent::Error(e));
                    self.emit(EngineEvent::Modem(self.config));
                    return;
                }
                let only_tones = RttyConfig {
                    mark_hz: config.mark_hz,
                    ..self.config
                } == config;
                if only_tones && self.tx_playback.is_none() && !self.ptt {
                    self.decoder.retune(config.mark_hz).expect("validated tone");
                } else {
                    if !self.finalize_tx() {
                        self.emit(EngineEvent::Modem(self.config));
                        return;
                    }
                    self.decoder = Decoder::new(config).expect("validated modem");
                }
                self.config = config;
                let _ = self
                    .db
                    .set_config("rtty_modem", &serde_json::to_string(&config).unwrap());
                self.emit(EngineEvent::Modem(config));
            }
            EngineCommand::SetTxGain(gain) => {
                self.tx_gain = clamp_tx_gain(gain);
                let _ = self.db.set_config("tx_gain", &self.tx_gain.to_string());
            }
            EngineCommand::SetInputDevice(name) => {
                let running = self.input.is_some();
                self.input = None;
                self.input_device = name;
                let _ = self
                    .db
                    .set_config("input_device", self.input_device.as_deref().unwrap_or(""));
                if running {
                    self.start_decode();
                }
            }
            EngineCommand::SetOutputDevice(name) => {
                if !self.finalize_tx() {
                    return;
                }
                self.output_device = name;
                let _ = self
                    .db
                    .set_config("output_device", self.output_device.as_deref().unwrap_or(""));
            }
            EngineCommand::SelectRig(cfg) => {
                if !self.finalize_tx() {
                    return;
                }
                let _ = self
                    .db
                    .set_config("rig_config", &serde_json::to_string(&cfg).unwrap());
                self.select_rig(cfg);
            }
            EngineCommand::DisconnectRig => {
                if !self.finalize_tx() {
                    return;
                }
                self.rig = None;
                self.publish_rig_status();
            }
            EngineCommand::StopTx => {
                self.finalize_tx();
            }
            EngineCommand::FreeText(text) => self.start_transmit(&text),
            EngineCommand::RefreshStatus => {
                self.publish_rig_status();
                self.publish_tx_state();
                self.publish_clock_sync();
                self.emit(EngineEvent::Modem(self.config));
                self.emit(EngineEvent::Decoding(self.input.is_some()));
            }
            EngineCommand::SetClockOffset(ms) => {
                self.clock_offset_ms = ms;
                self.time_synced = true;
                let _ = self.db.set_config("clock_offset_ms", &ms.to_string());
                self.publish_clock_sync();
            }
            EngineCommand::ResyncTime => crate::timesync::spawn_once(self.cmd_tx.clone()),
            EngineCommand::SetWaterfallConfig(cfg) => {
                let cfg = cfg.sanitize();
                let _ = self
                    .db
                    .set_config("waterfall", &serde_json::to_string(&cfg).unwrap());
                self.wf = WfProcessor::new(cfg);
            }
            EngineCommand::Shutdown => {
                self.finalize_tx();
            }
        }
    }

    fn start_decode(&mut self) {
        self.input = None;
        self.accum.clear();
        self.reset_decoder();
        match AudioInput::start(self.input_device.as_deref(), self.accum.clone()) {
            Ok(input) => {
                self.emit(EngineEvent::Info(format!(
                    "capturing '{}' @ {} Hz",
                    input.device_name, input.device_rate
                )));
                self.input = Some(input);
            }
            Err(e) => self.emit(EngineEvent::Error(format!("audio start failed: {e}"))),
        }
        self.emit(EngineEvent::Decoding(self.input.is_some()));
    }

    fn select_rig(&mut self, config: RigConfig) {
        self.rig = None;
        match RigConnection::connect(&config) {
            Ok(mut rig) => {
                if let Err(e) = rig.set_frequency(self.dial_hz) {
                    self.emit(EngineEvent::Error(format!("tuning failed: {e}")));
                }
                self.rig = Some(rig);
            }
            Err(e) => self.emit(EngineEvent::Error(format!("rig connect failed: {e}"))),
        }
        self.publish_rig_status();
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.finalize_tx();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicBool, Ordering};

    #[test]
    fn shutdown_waits_for_prior_commands_and_receiver_cleanup() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let (engine, events) = spawn(db.clone());
        engine.send(EngineCommand::SetStation {
            call: "ks3ckc".into(),
            grid: "fn20".into(),
        });
        engine.shutdown();
        engine.shutdown(); // closing an already stopped engine is harmless
        assert_eq!(db.get_config("my_call").as_deref(), Some("KS3CKC"));
        assert!(events
            .try_iter()
            .any(|e| matches!(e, EngineEvent::TxState(s) if !s.transmitting)));
    }

    #[test]
    fn modem_commands_persist_valid_settings_and_reject_invalid_settings() {
        let db = Arc::new(Db::open_in_memory().unwrap());
        let (evt, rx) = std::sync::mpsc::channel();
        let (cmd, _) = std::sync::mpsc::channel();
        let mut engine = Engine::new(db.clone(), evt, cmd);
        let config = RttyConfig {
            baud_rate: 75.0,
            shift_hz: 425.0,
            reverse: true,
            ..Default::default()
        };
        engine.handle(EngineCommand::SetModem(config));
        assert_eq!(
            serde_json::from_str::<RttyConfig>(&db.get_config("rtty_modem").unwrap()).unwrap(),
            config
        );
        assert!(rx
            .try_iter()
            .any(|e| matches!(e, EngineEvent::Modem(c) if c == config)));
        let signal = dsp::encode("12", config).unwrap();
        let split = ((16.0 + 3.0 * 7.5) * config.samples_per_bit()).round() as usize;
        assert_eq!(engine.decoder.process(&signal[..split]), "1");
        engine.handle(EngineCommand::SetModem(config));
        assert_eq!(
            engine.decoder.process(&signal[split..]),
            "2",
            "retuning must retain FIGS state"
        );
        engine.handle(EngineCommand::SetModem(RttyConfig {
            sample_rate: 48000,
            ..config
        }));
        let events: Vec<_> = rx.try_iter().collect();
        assert!(events.iter().any(|e| matches!(e, EngineEvent::Error(_))));
        assert!(events
            .iter()
            .any(|e| matches!(e, EngineEvent::Modem(c) if *c == config)));
        engine.handle(EngineCommand::FreeText("@@@".into()));
        assert!(rx.try_iter().any(|e| matches!(e, EngineEvent::Error(_))));
        engine.handle(EngineCommand::RefreshStatus);
        assert!(rx
            .try_iter()
            .any(|e| matches!(e, EngineEvent::TxState(s) if !s.transmitting)));
    }

    #[test]
    fn uncertain_ptt_blocks_rig_changes_until_stop_successfully_unkeys() {
        // A local XML-RPC peer models a key command whose acknowledgement is
        // lost. This tests software fault handling, not a physical radio.
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        let failing = Arc::new(AtomicBool::new(false));
        let keyed = Arc::new(AtomicBool::new(false));
        let stop = Arc::new(AtomicBool::new(false));
        let (fail_peer, key_peer, stop_peer) = (failing.clone(), keyed.clone(), stop.clone());
        let peer = std::thread::spawn(move || {
            while !stop_peer.load(Ordering::SeqCst) {
                let Ok((mut stream, _)) = listener.accept() else {
                    std::thread::sleep(Duration::from_millis(2));
                    continue;
                };
                stream
                    .set_read_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                let mut request = Vec::new();
                let mut bytes = [0; 4096];
                loop {
                    let n = stream.read(&mut bytes).unwrap();
                    if n == 0 {
                        break;
                    }
                    request.extend_from_slice(&bytes[..n]);
                    if request.windows(13).any(|w| w == b"</methodCall>") {
                        break;
                    }
                }
                let request = String::from_utf8(request).unwrap();
                let fail = fail_peer.load(Ordering::SeqCst);
                if request.contains("rig.set_ptt") {
                    if request.contains("<i4>1</i4>") {
                        key_peer.store(true, Ordering::SeqCst);
                    } else if !fail {
                        key_peer.store(false, Ordering::SeqCst);
                    }
                }
                let status = if fail {
                    "500 Internal Server Error"
                } else {
                    "200 OK"
                };
                let body = "<methodResponse><params><param><value>Test peer</value></param></params></methodResponse>";
                let _ = write!(
                    stream,
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
            }
        });
        let db = Arc::new(Db::open_in_memory().unwrap());
        let (evt, rx) = std::sync::mpsc::channel();
        let (cmd, _) = std::sync::mpsc::channel();
        let mut engine = Engine::new(db.clone(), evt, cmd);
        let config: RigConfig =
            serde_json::from_str(&format!(r#"{{"backend":"flrig","flrig_port":{port}}}"#)).unwrap();
        engine.handle(EngineCommand::SelectRig(config));
        failing.store(true, Ordering::SeqCst);
        assert!(!engine.set_ptt(true));
        assert!(keyed.load(Ordering::SeqCst));
        engine.handle(EngineCommand::DisconnectRig);
        engine.handle(EngineCommand::SetOutputDevice(Some(
            "must not persist".into(),
        )));
        engine.handle(EngineCommand::RefreshStatus);
        assert!(rx
            .try_iter()
            .any(|e| matches!(e, EngineEvent::RigStatus(s) if s.ptt)));
        assert_eq!(db.get_config("output_device"), None);
        failing.store(false, Ordering::SeqCst);
        engine.handle(EngineCommand::StopTx);
        assert!(!keyed.load(Ordering::SeqCst));
        engine.handle(EngineCommand::DisconnectRig);
        assert!(rx
            .try_iter()
            .any(|e| matches!(e, EngineEvent::RigStatus(s) if !s.ptt && !s.connected)));
        drop(engine);
        stop.store(true, Ordering::SeqCst);
        peer.join().unwrap();
    }

    #[test]
    fn manual_over_is_framed_and_invalid_text_never_keys() {
        assert_eq!(
            validate_tx("  cq K1ABC 599  ", RttyConfig::default()).unwrap(),
            " cq K1ABC 599\r\n"
        );
        for text in ["", "  ", "@@@", &"A".repeat(1001)] {
            assert!(validate_tx(text, RttyConfig::default()).is_err());
        }
    }
    #[test]
    fn signal_and_gain_remain_finite() {
        assert_eq!(rms_dbfs(&[]), -120.0);
        assert!(rms_dbfs(&[0.0; 100]) <= SILENCE_DBFS);
        assert!((rms_dbfs(&[0.5; 100]) + 6.0206).abs() < 0.001);
        assert_eq!(clamp_tx_gain(f32::NAN), 0.0);
        assert_eq!(clamp_tx_gain(-1.0), 0.0);
        assert_eq!(clamp_tx_gain(2.0), 1.0);
    }
}
