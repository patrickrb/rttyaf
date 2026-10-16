package com.k1af.rttyaf;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** RTTYAF must never spin up the inherited FT8 engine in the background. */
public class Ft8EnginePolicyTest {

    @Test
    public void ft8EngineNeverRuns() {
        assertThat(Ft8EnginePolicy.runsFt8Engine()).isFalse();
    }
}
