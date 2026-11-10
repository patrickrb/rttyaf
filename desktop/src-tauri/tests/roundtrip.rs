use rttyaf::dsp::{encode, Decoder, RttyConfig};

#[test]
fn on_air_tones_and_cumulative_bit_timing() {
    for reverse in [false, true] {
        let config = RttyConfig {
            reverse,
            ..Default::default()
        };
        let signal = encode("E", config).unwrap(); // LTRS + E: 20 + 2*7.5 bits
        assert_eq!(signal.len(), 9241);
        let preamble_end = (16.0 * 12000.0 / 45.45_f64).round() as usize;
        let crossing_count = signal[..preamble_end]
            .windows(2)
            .filter(|p| p[0] <= 0.0 && p[1] > 0.0)
            .count();
        let measured_mark = crossing_count as f64 * 12000.0 / preamble_end as f64;
        assert!((measured_mark - if reverse { 2295.0 } else { 2125.0 }).abs() < 4.0);
        // First frame is LTRS: exactly one space START then five mark bits.
        let bit_end = (17.0 * 12000.0 / 45.45_f64).round() as usize;
        let crossings = signal[preamble_end..bit_end]
            .windows(2)
            .filter(|p| p[0] <= 0.0 && p[1] > 0.0)
            .count();
        let measured_space = crossings as f64 * 12000.0 / (bit_end - preamble_end) as f64;
        assert!((measured_space - if reverse { 2125.0 } else { 2295.0 }).abs() < 50.0);
    }
}

#[test]
fn modest_noise_and_tuning_error_keep_nontrivial_text() {
    let config = RttyConfig::default();
    let signal = encode("CQ K1ABC DE KS3CKC 599 73\r\n", config).unwrap();
    let noisy: Vec<_> = signal
        .iter()
        .enumerate()
        .map(|(i, &s)| s + (((i * 7919 % 997) as f32 / 997.0) - 0.5) * 0.06)
        .collect();
    let mut decoder = Decoder::new(RttyConfig {
        mark_hz: 2130.0,
        ..config
    })
    .unwrap();
    let decoded: String = noisy.chunks(1440).map(|c| decoder.process(c)).collect();
    assert_eq!(decoded, "CQ K1ABC DE KS3CKC 599 73\r\n");
}
