package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#retuneAllowed} — the gate that keeps a
 * Settings band pick (or any CAT dial/mode write) from moving a keyed rig.
 * The Settings tab stays reachable during an RTTY over, so this must refuse
 * for either transmitter.
 */
public class RetuneGuardTest {

    @Test
    public void idle_allowsRetune() {
        assertThat(MainViewModel.retuneAllowed(false, false)).isTrue();
    }

    @Test
    public void ft8Transmitting_refuses() {
        assertThat(MainViewModel.retuneAllowed(true, false)).isFalse();
    }

    @Test
    public void rttyTransmitting_refuses() {
        assertThat(MainViewModel.retuneAllowed(false, true)).isFalse();
    }

    @Test
    public void bothTransmitting_refuses() {
        assertThat(MainViewModel.retuneAllowed(true, true)).isFalse();
    }
}
