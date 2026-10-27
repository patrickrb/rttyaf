package com.k1af.rttyaf.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link HamRecorder#restartMicOnGrant} — whether a RECORD_AUDIO
 * grant rebuilds the capture that failed to open at first launch.
 */
public class HamRecorderMicGrantTest {

    @Test
    public void runningMicSourceRestarts() {
        assertThat(HamRecorder.restartMicOnGrant(true, true)).isTrue();
    }

    @Test
    public void lanAudioSourceIsLeftAlone() {
        // ICOM WiFi / Flex network audio: MicRecorder is deliberately stopped.
        assertThat(HamRecorder.restartMicOnGrant(true, false)).isFalse();
    }

    @Test
    public void stoppedRecorderIsLeftAlone() {
        // App teardown stopped the recorder; a late grant must not reopen the mic.
        assertThat(HamRecorder.restartMicOnGrant(false, true)).isFalse();
    }
}
