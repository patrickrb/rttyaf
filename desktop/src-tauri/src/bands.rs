//! RTTY starting points, not regulatory permission. Operators choose a clear,
//! locally permitted frequency and set the rig to LSB (or USB with reverse).

#[derive(Debug, Clone, Copy)]
pub struct Band {
    pub name: &'static str,
    /// Suggested RTTY dial frequency in Hz; not a fixed channel.
    pub dial_hz: u64,
    /// Inclusive band edges (Hz) for naming an arbitrary frequency.
    pub lo_hz: u64,
    pub hi_hz: u64,
}

pub const RTTY_BANDS: &[Band] = &[
    Band {
        name: "160m",
        dial_hz: 1_840_000,
        lo_hz: 1_800_000,
        hi_hz: 2_000_000,
    },
    Band {
        name: "80m",
        dial_hz: 3_580_000,
        lo_hz: 3_500_000,
        hi_hz: 4_000_000,
    },
    Band {
        name: "40m",
        dial_hz: 7_045_000,
        lo_hz: 7_000_000,
        hi_hz: 7_300_000,
    },
    Band {
        name: "30m",
        dial_hz: 10_140_000,
        lo_hz: 10_100_000,
        hi_hz: 10_150_000,
    },
    Band {
        name: "20m",
        dial_hz: 14_085_000,
        lo_hz: 14_000_000,
        hi_hz: 14_350_000,
    },
    Band {
        name: "17m",
        dial_hz: 18_105_000,
        lo_hz: 18_068_000,
        hi_hz: 18_168_000,
    },
    Band {
        name: "15m",
        dial_hz: 21_085_000,
        lo_hz: 21_000_000,
        hi_hz: 21_450_000,
    },
    Band {
        name: "12m",
        dial_hz: 24_925_000,
        lo_hz: 24_890_000,
        hi_hz: 24_990_000,
    },
    Band {
        name: "10m",
        dial_hz: 28_085_000,
        lo_hz: 28_000_000,
        hi_hz: 29_700_000,
    },
];

/// ADIF band name for a dial frequency in Hz (e.g. 14_074_000 -> "20m").
pub fn band_for_freq_hz(hz: u64) -> &'static str {
    for b in RTTY_BANDS {
        if hz >= b.lo_hz && hz <= b.hi_hz {
            return b.name;
        }
    }
    "unknown"
}

/// Frequency in MHz as an ADIF-style string, e.g. 14_074_000 -> "14.074000".
pub fn freq_mhz_string(hz: u64) -> String {
    format!("{:.6}", hz as f64 / 1_000_000.0)
}

pub fn default_band() -> &'static Band {
    &RTTY_BANDS[4] // 20m
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rtty_presets_stay_in_their_named_bands() {
        assert_eq!(default_band().dial_hz, 14_085_000);
        for band in RTTY_BANDS {
            assert_eq!(band_for_freq_hz(band.dial_hz), band.name);
        }
        assert_eq!(band_for_freq_hz(1), "unknown");
        assert_eq!(freq_mhz_string(14_085_000), "14.085000");
    }
}
