// Typed wrappers over the Tauri command/event boundary. The Rust side defines
// the matching commands (src-tauri/src/main.rs) and emits a single tagged
// "engine-event" stream (src-tauri/src/engine.rs::EngineEvent).
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface RttyConfig {
  baud_rate: number;
  shift_hz: number;
  mark_hz: number;
  sample_rate: number;
  stop_bits: number;
  reverse: boolean;
  unshift_on_space: boolean;
  squelch: boolean;
  squelch_floor: number;
}

export interface TxStateEvent {
  transmitting: boolean;
  message: string | null;
}

export interface RigStatusEvent {
  connected: boolean;
  model: string;
  frequency_hz: number;
  ptt: boolean;
}

export interface ClockSyncEvent {
  /** ms added to the local clock to reach true UTC (negative = local clock is fast). */
  offset_ms: number;
  /** true once an NTP sync has succeeded (or a saved offset was restored). */
  synced: boolean;
}

export interface QsoRecord {
  id?: number | null;
  call: string;
  gridsquare: string;
  mode: string;
  rst_sent: string;
  rst_rcvd: string;
  qso_date: string;
  time_on: string;
  qso_date_off: string;
  time_off: string;
  band: string;
  freq: string;
  station_callsign: string;
  my_gridsquare: string;
  comment: string;
}

export interface AudioDevice {
  id: string;
  name: string;
  is_default: boolean;
  default_sample_rate: number;
  channels: number;
}

export interface SerialPortInfo {
  name: string;
  kind: string;
}

export interface HamlibRig {
  model: number;
  name: string;
}

export interface BandInfo {
  name: string;
  dial_hz: number;
  lo_hz: number;
  hi_hz: number;
}

/** Waterfall FFT window (developer setting, issue #428). */
export type WfWindow = "rect" | "hann" | "hamming" | "blackman" | "blackman_harris";

/** Live-waterfall FFT parameters. Defaults: hann / 2048 / 6. */
export interface WaterfallConfig {
  window: WfWindow;
  fft_size: number;
  avg: number;
}

export type RigBackend = "none" | "serial" | "flrig" | "hamlib";
export type RigModel = "yaesu" | "kenwood" | "icom" | "none";
export type PttMethod = "cat" | "rts" | "dtr" | "none";

export interface RigConfig {
  backend: RigBackend;
  model: RigModel;
  port: string;
  baud: number;
  ptt: PttMethod;
  civ_address: number;
  flrig_host: string;
  flrig_port: number;
  hamlib_model: number;
  hamlib_network: boolean;
  hamlib_address: string;
}

export type EngineEvent =
  | { type: "received"; data: string }
  | { type: "clock"; data: number }
  | { type: "modem"; data: RttyConfig }
  | { type: "decoding"; data: boolean }
  | { type: "tx_state"; data: TxStateEvent }
  | { type: "rig_status"; data: RigStatusEvent }
  | { type: "clock_sync"; data: ClockSyncEvent }
  | { type: "waterfall_row"; data: { bins: number[]; hz_per_col: number } }
  | { type: "input_level"; data: { db: number; silent: boolean } }
  | { type: "info"; data: string }
  | { type: "error"; data: string };

export function onEngineEvent(cb: (e: EngineEvent) => void): Promise<UnlistenFn> {
  return listen<EngineEvent>("engine-event", (ev) => cb(ev.payload));
}

// --- command wrappers -------------------------------------------------------

export const api = {
  listAudioInputs: () => invoke<AudioDevice[]>("list_audio_inputs"),
  listAudioOutputs: () => invoke<AudioDevice[]>("list_audio_outputs"),
  listSerialPorts: () => invoke<SerialPortInfo[]>("list_serial_ports"),
  listHamlibRigs: () => invoke<HamlibRig[]>("list_hamlib_rigs"),
  listBands: () => invoke<BandInfo[]>("list_bands"),

  startDecode: () => invoke("start_decode"),
  stopDecode: () => invoke("stop_decode"),
  setStation: (call: string, grid: string) => invoke("set_station", { call, grid }),
  setBand: (dialHz: number) => invoke("set_band", { dialHz }),
  setModem: (config: RttyConfig) => invoke("set_modem", { config }),
  setTxGain: (gain: number) => invoke("set_tx_gain", { gain }),
  setInputDevice: (name: string | null) => invoke("set_input_device", { name }),
  setOutputDevice: (name: string | null) => invoke("set_output_device", { name }),
  selectRig: (config: RigConfig) => invoke("select_rig", { config }),
  disconnectRig: () => invoke("disconnect_rig"),
  refreshStatus: () => invoke("refresh_status"),
  resyncTime: () => invoke("resync_time"),

  stopTx: () => invoke("stop_tx"),
  freeText: (text: string) => invoke("free_text", { text }),

  listLog: (limit: number, offset: number) =>
    invoke<QsoRecord[]>("list_log", { limit, offset }),
  logCount: () => invoke<number>("log_count"),
  deleteQso: (id: number) => invoke("delete_qso", { id }),
  saveQso: (record: QsoRecord) => invoke<number>("save_qso", { record }),
  exportAdif: () => invoke<string>("export_adif"),

  getConfig: (key: string) => invoke<string | null>("get_config", { key }),
  setConfig: (key: string, value: string) => invoke("set_config", { key, value }),
  allConfig: () => invoke<[string, string][]>("all_config"),

  /** Apply + persist live-waterfall FFT parameters (Rust side persists). */
  setWaterfallConfig: (config: WaterfallConfig) =>
    invoke("set_waterfall_config", { config }),
};
