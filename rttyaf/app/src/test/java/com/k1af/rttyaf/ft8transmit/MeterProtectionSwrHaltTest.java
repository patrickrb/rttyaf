package com.k1af.rttyaf.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.rttyaf.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

/**
 * The SWR halt no longer reaches into the FT8 transmitter: whoever owns the air
 * (RTTY TX, the tune carrier) registers what to abort. Robolectric because the
 * controller publishes its state through LiveData.
 */
@RunWith(RobolectricTestRunner.class)
public class MeterProtectionSwrHaltTest {

    private boolean enabledBefore;
    private int thresholdBefore;

    @Before
    public void armProtection() {
        enabledBefore = GeneralVariables.swrHaltEnabled;
        thresholdBefore = GeneralVariables.swrHaltThreshold;
        GeneralVariables.swrHaltEnabled = true;
        GeneralVariables.swrHaltThreshold = 100;
    }

    @After
    public void restore() {
        GeneralVariables.swrHaltEnabled = enabledBefore;
        GeneralVariables.swrHaltThreshold = thresholdBefore;
    }

    @Test
    public void swrOverThreshold_runsTheAbortHookAndLocksOut() {
        MeterProtectionController mpc = new MeterProtectionController();
        final int[] aborted = {0};
        mpc.setOnSwrHalt(() -> aborted[0]++);

        mpc.onMeterUpdate(-1, 200); // ALC unknown, SWR well over the threshold
        ShadowLooper.idleMainLooper(); // the lockout is published through LiveData

        assertThat(aborted[0]).isEqualTo(1);
        assertThat(mpc.isSwrLocked()).isTrue();

        mpc.clearSwrLockout();
        ShadowLooper.idleMainLooper();
        assertThat(mpc.isSwrLocked()).isFalse();
    }

    @Test
    public void noHookRegistered_stillLocksOutWithoutThrowing() {
        MeterProtectionController mpc = new MeterProtectionController();
        mpc.onMeterUpdate(-1, 200);
        ShadowLooper.idleMainLooper();
        assertThat(mpc.isSwrLocked()).isTrue();
    }

    @Test
    public void swrUnderThreshold_leavesTxAlone() {
        MeterProtectionController mpc = new MeterProtectionController();
        final int[] aborted = {0};
        mpc.setOnSwrHalt(() -> aborted[0]++);
        mpc.onMeterUpdate(-1, 50);
        ShadowLooper.idleMainLooper();
        assertThat(aborted[0]).isEqualTo(0);
        assertThat(mpc.isSwrLocked()).isFalse();
    }
}
