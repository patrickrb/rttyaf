//! Bounded continuous capture: decode each sample once, retain recent spectrum
//! history separately. Overflow is reported so the framer can discard lost state.
use crate::dsp::SAMPLE_RATE;
use parking_lot::Mutex;
use std::collections::VecDeque;

const CAPACITY: usize = SAMPLE_RATE as usize * 2;
struct Buffers {
    pending: VecDeque<f32>,
    recent: VecDeque<f32>,
    overflow: bool,
}
pub struct CaptureBuffer {
    inner: Mutex<Buffers>,
}

impl CaptureBuffer {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Buffers {
                pending: VecDeque::new(),
                recent: VecDeque::new(),
                overflow: false,
            }),
        }
    }
    pub fn push(&self, samples: &[f32]) {
        let mut b = self.inner.lock();
        for &sample in samples {
            if b.pending.len() == CAPACITY {
                b.pending.pop_front();
                b.overflow = true;
            }
            b.pending.push_back(sample);
            if b.recent.len() == CAPACITY {
                b.recent.pop_front();
            }
            b.recent.push_back(sample);
        }
    }
    pub fn drain(&self) -> (Vec<f32>, bool) {
        let mut b = self.inner.lock();
        let overflow = std::mem::take(&mut b.overflow);
        (b.pending.drain(..).collect(), overflow)
    }
    pub fn peek_recent(&self, n: usize) -> Vec<f32> {
        let b = self.inner.lock();
        b.recent
            .iter()
            .skip(b.recent.len().saturating_sub(n))
            .copied()
            .collect()
    }
    pub fn clear(&self) {
        let mut b = self.inner.lock();
        b.pending.clear();
        b.recent.clear();
        b.overflow = false;
    }
}
impl Default for CaptureBuffer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn continuous_capture_has_no_duplicates_and_keeps_spectrum_history() {
        let buffer = CaptureBuffer::new();
        buffer.push(&[1.0, 2.0, 3.0]);
        assert_eq!(buffer.drain(), (vec![1.0, 2.0, 3.0], false));
        buffer.push(&[4.0]);
        assert_eq!(buffer.drain(), (vec![4.0], false));
        assert_eq!(buffer.drain(), (vec![], false));
        assert_eq!(buffer.peek_recent(3), [2.0, 3.0, 4.0]);
        buffer.clear();
        assert!(buffer.peek_recent(10).is_empty());
    }
    #[test]
    fn overrun_is_bounded_and_reported_once() {
        let buffer = CaptureBuffer::new();
        let samples: Vec<_> = (0..CAPACITY + 9).map(|n| n as f32).collect();
        buffer.push(&samples);
        let (pending, lost) = buffer.drain();
        assert!(lost);
        assert_eq!(pending, samples[9..]);
        assert_eq!(buffer.peek_recent(CAPACITY * 2), samples[9..]);
        assert_eq!(buffer.drain(), (vec![], false));
    }
}
