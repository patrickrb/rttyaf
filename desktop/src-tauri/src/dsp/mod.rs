//! Streaming US-TTY Baudot AFSK, ported from Android's rtty/{Baudot,
//! RttyConfig,RttyEncoder,RttyDecoder}. No native DSP or FFI is needed.

use serde::{Deserialize, Serialize};
use std::f64::consts::TAU;

pub const SAMPLE_RATE: i32 = 12_000;
const LTRS: u8 = 31;
const FIGS: u8 = 27;
const LETTERS: &[u8; 32] = b"\0E\nA SIU\rDRJNFCKTZLWHYPQOBG\0MXV\0";
const FIGURES: &[u8; 32] = b"\x003\n- \x0787\r$4',!:(5\")2#6019?&\0./;\0";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct RttyConfig {
    pub baud_rate: f64,
    pub shift_hz: f64,
    pub mark_hz: f64,
    pub sample_rate: i32,
    pub stop_bits: f64,
    pub reverse: bool,
    pub unshift_on_space: bool,
    pub squelch: bool,
    pub squelch_floor: f64,
}

impl Default for RttyConfig {
    fn default() -> Self {
        Self {
            baud_rate: 45.45,
            shift_hz: 170.0,
            mark_hz: 2125.0,
            sample_rate: SAMPLE_RATE,
            stop_bits: 1.5,
            reverse: false,
            unshift_on_space: true,
            squelch: true,
            squelch_floor: 0.004,
        }
    }
}

impl RttyConfig {
    /// Validate at the public boundary, before allocating or computing offsets.
    pub fn validate(&self) -> Result<(), String> {
        if !self.baud_rate.is_finite()
            || !(1.0..=300.0).contains(&self.baud_rate)
            || !(8000..=192000).contains(&self.sample_rate)
            || !self.stop_bits.is_finite()
            || !(0.1..=3.0).contains(&self.stop_bits)
            || !self.mark_hz.is_finite()
            || self.mark_hz <= 0.0
            || !self.shift_hz.is_finite()
            || self.shift_hz <= 0.0
            || self.mark_hz + self.shift_hz >= self.sample_rate as f64 / 2.0
            || !self.squelch_floor.is_finite()
            || self.squelch_floor < 0.0
        {
            return Err("Invalid RTTY baud, tones, sample rate, stop bits or squelch".into());
        }
        Ok(())
    }

    pub fn mark_tone_hz(&self) -> f64 {
        self.mark_hz + if self.reverse { self.shift_hz } else { 0.0 }
    }

    pub fn space_tone_hz(&self) -> f64 {
        self.mark_hz + if self.reverse { 0.0 } else { self.shift_hz }
    }

    pub fn samples_per_bit(&self) -> f64 {
        self.sample_rate as f64 / self.baud_rate
    }
}

/// ITA2 codes including leading LTRS, USOS reshift and unsupported-char skipping.
pub fn text_to_codes(text: &str, config: RttyConfig) -> Vec<u8> {
    let mut out = vec![LTRS];
    let mut figures = false;
    for raw in text.chars() {
        // Android uppercases a character, not a string (no Unicode expansion).
        let mut uppercase = raw.to_uppercase();
        let first = uppercase.next().unwrap_or(raw);
        let ch = if uppercase.next().is_none() {
            first
        } else {
            raw
        };
        let neutral = matches!(ch, ' ' | '\r' | '\n');
        let entry = LETTERS
            .iter()
            .position(|&c| c != 0 && c as char == ch)
            .map(|code| (code, false))
            .or_else(|| {
                FIGURES
                    .iter()
                    .position(|&c| c != 0 && c as char == ch)
                    .map(|code| (code, true))
            });
        let Some((code, required)) = entry else {
            continue;
        };
        if !neutral && required != figures {
            out.push(if required { FIGS } else { LTRS });
            figures = required;
        }
        out.push(code as u8);
        if config.unshift_on_space && code == 4 {
            figures = false;
        }
    }
    out
}

/// 16-bit-compatible half-scale continuous-phase FSK. Round *cumulative*
/// boundaries, not each bit, so fractional baud never accumulates clock drift.
pub fn encode(text: &str, config: RttyConfig) -> Result<Vec<f32>, String> {
    config.validate()?;
    let codes = text_to_codes(text, config);
    let total = ((20.0 + codes.len() as f64 * (6.0 + config.stop_bits)) * config.samples_per_bit())
        .round() as usize;
    let mut out = Vec::with_capacity(total);
    let mut phase: f64 = 0.0;
    let mut bits = 0.0;
    let mut segment = |mark: bool, length: f64| {
        bits += length;
        let boundary = (bits * config.samples_per_bit()).round() as usize;
        let inc =
            TAU * if mark {
                config.mark_tone_hz()
            } else {
                config.space_tone_hz()
            } / config.sample_rate as f64;
        while out.len() < boundary.min(total) {
            out.push((0.5 * phase.sin()) as f32);
            phase += inc;
            if phase >= TAU {
                phase -= TAU;
            }
        }
    };
    segment(true, 16.0);
    for code in codes {
        segment(false, 1.0);
        for bit in 0..5 {
            segment(code & (1 << bit) != 0, 1.0);
        }
        segment(true, config.stop_bits);
    }
    segment(true, 4.0);
    Ok(out)
}

/// Android's over framing: leading idle space, trimmed payload, CR/LF.
pub fn tx_message(text: &str) -> String {
    format!(" {}\r\n", text.trim())
}

/// Stateful quadrature detectors + Schmitt slicer + asynchronous 5-bit framer.
/// Emits after the last data-bit centre, like Android, including short stops.
pub struct Decoder {
    config: RttyConfig,
    angles: [f64; 2],
    inc: [f64; 2],
    i: [f64; 2],
    q: [f64; 2],
    alpha: f64,
    energy: f64,
    cur_mark: bool,
    prev_mark: bool,
    receiving: bool,
    offsets: [usize; 6],
    since_edge: usize,
    target: usize,
    code: u8,
    figures: bool,
}

impl Decoder {
    pub fn new(config: RttyConfig) -> Result<Self, String> {
        config.validate()?;
        Ok(Self {
            config,
            angles: [0.0; 2],
            inc: [
                TAU * config.mark_tone_hz() / config.sample_rate as f64,
                TAU * config.space_tone_hz() / config.sample_rate as f64,
            ],
            i: [0.0; 2],
            q: [0.0; 2],
            alpha: (-TAU * config.baud_rate / config.sample_rate as f64).exp(),
            energy: 0.0,
            cur_mark: true,
            prev_mark: true,
            receiving: false,
            offsets: std::array::from_fn(|n| {
                ((n as f64 + 0.5) * config.samples_per_bit()).round() as usize
            }),
            since_edge: 0,
            target: 0,
            code: 0,
            figures: false,
        })
    }

    /// Retune without resetting filters, frame or shift, as on Android.
    pub fn retune(&mut self, mark_hz: f64) -> Result<(), String> {
        let next = RttyConfig {
            mark_hz,
            ..self.config
        };
        next.validate()?;
        self.config = next;
        self.inc = [
            TAU * next.mark_tone_hz() / next.sample_rate as f64,
            TAU * next.space_tone_hz() / next.sample_rate as f64,
        ];
        Ok(())
    }

    pub fn process(&mut self, samples: &[f32]) -> String {
        let mut text = String::new();
        for &sample in samples {
            for tone in 0..2 {
                let (sin, cos) = self.angles[tone].sin_cos();
                self.angles[tone] += self.inc[tone];
                if self.angles[tone] >= TAU {
                    self.angles[tone] -= TAU;
                }
                self.i[tone] += (1.0 - self.alpha) * (sample as f64 * cos - self.i[tone]);
                self.q[tone] += (1.0 - self.alpha) * (sample as f64 * sin - self.q[tone]);
            }
            let mag =
                std::array::from_fn::<_, 2, _>(|t| self.i[t] * self.i[t] + self.q[t] * self.q[t]);
            let total = mag[0] + mag[1] + 1e-12;
            self.energy += 0.05 * (total - self.energy);
            let open = !self.config.squelch || self.energy > self.config.squelch_floor;
            let norm = (mag[0] - mag[1]) / total;
            if norm > 0.1 {
                self.cur_mark = true;
            } else if norm < -0.1 {
                self.cur_mark = false;
            }
            if !self.receiving {
                if open && self.prev_mark && !self.cur_mark {
                    self.receiving = true;
                    self.since_edge = 0;
                    self.target = 0;
                    self.code = 0;
                }
            } else {
                self.since_edge += 1;
                while self.target < 6 && self.since_edge >= self.offsets[self.target] {
                    if self.target == 0 {
                        if self.cur_mark {
                            self.receiving = false;
                        }
                    } else {
                        if self.cur_mark {
                            self.code |= 1 << (self.target - 1);
                        }
                        if self.target == 5 {
                            match self.code {
                                LTRS => self.figures = false,
                                FIGS => self.figures = true,
                                code if open => {
                                    let ch = if self.figures {
                                        FIGURES[code as usize]
                                    } else {
                                        LETTERS[code as usize]
                                    };
                                    if ch != 0 {
                                        text.push(ch as char);
                                    }
                                    if self.config.unshift_on_space && code == 4 {
                                        self.figures = false;
                                    }
                                }
                                _ => {}
                            }
                            self.receiving = false;
                        }
                    }
                    self.target += 1;
                    if !self.receiving {
                        break;
                    }
                }
            }
            self.prev_mark = self.cur_mark;
        }
        text
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_goldens_match_waveform_and_streaming_decode() {
        let manifest = include_str!("../../../../rttyaf/app/src/test/resources/rtty/vectors.tsv");
        let fixtures: &[(&str, &[u8], usize)] = &[
            (
                "standard",
                include_bytes!("../../../../rttyaf/app/src/test/resources/rtty/standard.pcm"),
                48845,
            ),
            (
                "reverse",
                include_bytes!("../../../../rttyaf/app/src/test/resources/rtty/reverse.pcm"),
                40800,
            ),
            (
                "fast",
                include_bytes!("../../../../rttyaf/app/src/test/resources/rtty/fast.pcm"),
                20000,
            ),
            (
                "short-stop",
                include_bytes!("../../../../rttyaf/app/src/test/resources/rtty/short-stop.pcm"),
                35221,
            ),
            (
                "fractional",
                include_bytes!("../../../../rttyaf/app/src/test/resources/rtty/fractional.pcm"),
                149835,
            ),
        ];
        for line in manifest
            .lines()
            .filter(|s| !s.starts_with('#') && !s.is_empty())
        {
            let p: Vec<_> = line.split('\t').collect();
            let config = RttyConfig {
                baud_rate: p[1].parse().unwrap(),
                shift_hz: p[2].parse().unwrap(),
                mark_hz: p[3].parse().unwrap(),
                stop_bits: p[4].parse().unwrap(),
                reverse: p[5].parse().unwrap(),
                unshift_on_space: p[6].parse().unwrap(),
                ..RttyConfig::default()
            };
            let text = p[7].replace("\\r", "\r").replace("\\n", "\n");
            let (_, bytes, length) = fixtures.iter().find(|v| v.0 == p[0]).unwrap();
            let golden: Vec<_> = bytes
                .chunks_exact(2)
                .map(|b| i16::from_le_bytes([b[0], b[1]]))
                .collect();
            let signal = encode(&text, config).unwrap();
            assert_eq!(signal.len(), *length, "{} cumulative timing", p[0]);
            assert_eq!(signal.len(), golden.len());
            for (i, (&actual, &expected)) in signal.iter().zip(&golden).enumerate() {
                // JVM/Rust sin may differ in their last bits. One PCM LSB is
                // the maximum permitted error, not a waveform correlation proxy.
                let pcm = (actual as f64 * 32767.0 + 0.5).floor() as i32;
                assert!((pcm - expected as i32).abs() <= 1, "{} sample {i}", p[0]);
            }
            let golden: Vec<_> = golden.iter().map(|&s| s as f32 / 32768.0).collect();
            for (samples, chunk) in [(&golden, 137), (&signal, 997), (&signal, 1)] {
                let mut decoder = Decoder::new(config).unwrap();
                let recovered: String = samples.chunks(chunk).map(|s| decoder.process(s)).collect();
                assert_eq!(recovered, text.to_uppercase(), "{} chunk {chunk}", p[0]);
            }
        }
    }

    #[test]
    fn baudot_shifts_controls_unicode_and_usos() {
        let config = RttyConfig::default();
        assert_eq!(
            text_to_codes("a1 2b\r\n@", config),
            [31, 3, 27, 23, 4, 27, 19, 31, 25, 8, 2]
        );
        assert_eq!(
            text_to_codes(
                "1 2",
                RttyConfig {
                    unshift_on_space: false,
                    ..config
                }
            ),
            [31, 27, 23, 4, 19]
        );
        assert_eq!(text_to_codes("ſß", config), [31, 5]);
        assert_eq!(tx_message("  cq 73 \n"), " cq 73\r\n");
    }

    #[test]
    fn silence_noise_and_wrong_polarity_do_not_copy_the_message() {
        let config = RttyConfig::default();
        let mut decoder = Decoder::new(config).unwrap();
        assert_eq!(decoder.process(&vec![0.0; 12000]), "");
        // Deterministic low-level broadband noise, below the energy squelch.
        let noise: Vec<_> = (0..12000)
            .map(|i| (((i * 7919) % 997) as f32 / 997.0 - 0.5) * 0.005)
            .collect();
        assert_eq!(decoder.process(&noise), "");
        let signal = encode("RY 12 TEST", config).unwrap();
        let mut reversed = Decoder::new(RttyConfig {
            reverse: true,
            ..config
        })
        .unwrap();
        assert_ne!(reversed.process(&signal), "RY 12 TEST");
        let mut retuned = Decoder::new(RttyConfig {
            mark_hz: 1275.0,
            ..config
        })
        .unwrap();
        retuned.retune(2125.0).unwrap();
        assert_eq!(retuned.process(&signal), "RY 12 TEST");
        assert!(retuned.retune(f64::NAN).is_err());
    }

    #[test]
    fn invalid_config_is_rejected_before_encoding_or_decoding() {
        for config in [
            RttyConfig {
                baud_rate: 0.0,
                ..Default::default()
            },
            RttyConfig {
                mark_hz: f64::NAN,
                ..Default::default()
            },
            RttyConfig {
                shift_hz: -170.0,
                ..Default::default()
            },
            RttyConfig {
                mark_hz: 5990.0,
                ..Default::default()
            },
            RttyConfig {
                sample_rate: 0,
                ..Default::default()
            },
            RttyConfig {
                stop_bits: 0.0,
                ..Default::default()
            },
            RttyConfig {
                squelch_floor: -0.1,
                ..Default::default()
            },
        ] {
            assert!(encode("TEST", config).is_err());
            assert!(Decoder::new(config).is_err());
        }
    }
}
