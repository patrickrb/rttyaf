// RTTYAF desktop — Tauri entry point. Owns the SQLite DB and the runtime engine,
// exposes commands to the web UI, and forwards engine events to the webview.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Arc;

use serde::Serialize;
use tauri::{Emitter, Manager, State};

use rttyaf::audio::{self, AudioDevice};
use rttyaf::bands;
use rttyaf::db::{Db, QsoRecord};
use rttyaf::dsp::RttyConfig;
use rttyaf::engine::{self, EngineCommand, EngineHandle};
use rttyaf::rig::{self, HamlibRig, RigConfig, SerialPortInfo};
use rttyaf::wf::WfConfig;

struct AppState {
    engine: EngineHandle,
    db: Arc<Db>,
}

#[derive(Serialize)]
struct BandInfo {
    name: String,
    dial_hz: u64,
    lo_hz: u64,
    hi_hz: u64,
}

// --- device / port / band enumeration --------------------------------------

#[tauri::command]
fn list_audio_inputs() -> Vec<AudioDevice> {
    audio::list_input_devices()
}

#[tauri::command]
fn list_audio_outputs() -> Vec<AudioDevice> {
    audio::list_output_devices()
}

#[tauri::command]
fn list_serial_ports() -> Vec<SerialPortInfo> {
    rig::list_serial_ports()
}

#[tauri::command]
fn list_hamlib_rigs() -> Vec<HamlibRig> {
    rig::list_hamlib_rigs()
}

#[tauri::command]
fn list_bands() -> Vec<BandInfo> {
    bands::RTTY_BANDS
        .iter()
        .map(|b| BandInfo {
            name: b.name.to_string(),
            dial_hz: b.dial_hz,
            lo_hz: b.lo_hz,
            hi_hz: b.hi_hz,
        })
        .collect()
}

// --- engine control ---------------------------------------------------------

#[tauri::command]
fn start_decode(state: State<AppState>) {
    state.engine.send(EngineCommand::StartDecode);
}

#[tauri::command]
fn stop_decode(state: State<AppState>) {
    state.engine.send(EngineCommand::StopDecode);
}

#[tauri::command]
fn set_station(state: State<AppState>, call: String, grid: String) {
    state.engine.send(EngineCommand::SetStation { call, grid });
}

#[tauri::command]
fn set_band(state: State<AppState>, dial_hz: u64) {
    state.engine.send(EngineCommand::SetBand(dial_hz));
}

#[tauri::command]
fn set_modem(state: State<AppState>, config: RttyConfig) {
    state.engine.send(EngineCommand::SetModem(config));
}

#[tauri::command]
fn set_tx_gain(state: State<AppState>, gain: f32) {
    state.engine.send(EngineCommand::SetTxGain(gain));
}

#[tauri::command]
fn set_input_device(state: State<AppState>, name: Option<String>) {
    state.engine.send(EngineCommand::SetInputDevice(name));
}

#[tauri::command]
fn set_output_device(state: State<AppState>, name: Option<String>) {
    state.engine.send(EngineCommand::SetOutputDevice(name));
}

#[tauri::command]
fn select_rig(state: State<AppState>, config: RigConfig) {
    state.engine.send(EngineCommand::SelectRig(config));
}

#[tauri::command]
fn disconnect_rig(state: State<AppState>) {
    state.engine.send(EngineCommand::DisconnectRig);
}

#[tauri::command]
fn refresh_status(state: State<AppState>) {
    state.engine.send(EngineCommand::RefreshStatus);
}

#[tauri::command]
fn resync_time(state: State<AppState>) {
    state.engine.send(EngineCommand::ResyncTime);
}

#[tauri::command]
fn stop_tx(state: State<AppState>) {
    state.engine.send(EngineCommand::StopTx);
}

#[tauri::command]
fn free_text(state: State<AppState>, text: String) {
    state.engine.send(EngineCommand::FreeText(text));
}

// --- logbook / config -------------------------------------------------------

#[tauri::command]
fn list_log(state: State<AppState>, limit: i64, offset: i64) -> Vec<QsoRecord> {
    state.db.list_qsos(limit, offset)
}

#[tauri::command]
fn log_count(state: State<AppState>) -> i64 {
    state.db.count()
}

#[tauri::command]
fn delete_qso(state: State<AppState>, id: i64) -> Result<(), String> {
    state.db.delete_qso(id).map_err(|e| e.to_string())
}

#[tauri::command]
fn save_qso(state: State<AppState>, record: QsoRecord) -> Result<i64, String> {
    state.db.insert_qso(&record).map_err(|e| e.to_string())
}

#[tauri::command]
fn export_adif(state: State<AppState>) -> String {
    state.db.export_adif()
}

#[tauri::command]
fn get_config(state: State<AppState>, key: String) -> Option<String> {
    state.db.get_config(&key)
}

#[tauri::command]
fn set_config(state: State<AppState>, key: String, value: String) -> Result<(), String> {
    state.db.set_config(&key, &value).map_err(|e| e.to_string())
}

#[tauri::command]
fn all_config(state: State<AppState>) -> Vec<(String, String)> {
    state.db.all_config()
}

#[tauri::command]
fn set_waterfall_config(state: State<AppState>, config: WfConfig) {
    // The engine sanitizes, persists (wf_window/wf_fft_size/wf_avg), and
    // rebuilds its FFT plan; see EngineCommand::SetWaterfallConfig.
    state.engine.send(EngineCommand::SetWaterfallConfig(config));
}

fn main() {
    // Debug helper: `rttyaf --list-rigs` prints the Hamlib-enumerated rig count
    // and exits — verifies the bundled Hamlib library loads without the GUI.
    if std::env::args().any(|a| a == "--list-rigs") {
        let rigs = rig::list_hamlib_rigs();
        println!("Hamlib enumerated {} rigs", rigs.len());
        for r in rigs.iter() {
            println!("  #{:<6} {}", r.model, r.name);
        }
        return;
    }

    let data_dir = dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("RTTYAF");
    let _ = std::fs::create_dir_all(&data_dir);
    let db = Arc::new(Db::open(data_dir.join("rttyaf.sqlite")).expect("failed to open database"));

    let (engine, evt_rx) = engine::spawn(db.clone());

    tauri::Builder::default()
        .manage(AppState { engine, db })
        .setup(move |app| {
            // Forward engine events to the webview on the "engine-event" channel.
            let handle = app.handle().clone();
            std::thread::Builder::new()
                .name("rttyaf-event-forwarder".into())
                .spawn(move || {
                    while let Ok(ev) = evt_rx.recv() {
                        // Mirror status messages to the terminal. The app has no
                        // logger, so audio/rig/decode problems (e.g. "audio start
                        // failed") were previously invisible outside the in-app
                        // status line — making remote diagnosis impossible.
                        match &ev {
                            engine::EngineEvent::Error(m) => eprintln!("[rttyaf] ERROR: {m}"),
                            engine::EngineEvent::Info(m) => eprintln!("[rttyaf] {m}"),
                            _ => {}
                        }
                        let _ = handle.emit("engine-event", ev);
                    }
                })?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_audio_inputs,
            list_audio_outputs,
            list_serial_ports,
            list_hamlib_rigs,
            list_bands,
            start_decode,
            stop_decode,
            set_station,
            set_band,
            set_modem,
            set_tx_gain,
            set_input_device,
            set_output_device,
            select_rig,
            disconnect_rig,
            refresh_status,
            resync_time,
            stop_tx,
            free_text,
            list_log,
            log_count,
            delete_qso,
            save_qso,
            export_adif,
            get_config,
            set_config,
            all_config,
            set_waterfall_config,
        ])
        .build(tauri::generate_context!())
        .expect("error building RTTYAF")
        .run(|app, event| {
            if matches!(event, tauri::RunEvent::ExitRequested { .. }) {
                app.state::<AppState>().engine.shutdown();
            }
        });
}
