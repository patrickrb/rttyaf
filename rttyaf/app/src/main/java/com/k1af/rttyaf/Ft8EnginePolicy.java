package com.k1af.rttyaf;

/**
 * Whether the inherited FT8 engine — the 15 s slot decoder, the QSO sequencer it
 * feeds, PSK Reporter FT8 uploads and the FT8 waterfall FFT — runs in this app.
 *
 * <p>RTTYAF is an RTTY app and none of that is reachable from its UI, yet
 * {@link MainViewModel} used to start all of it at launch: a field log showed the
 * app decoding FT8 at 14.074 MHz in the background and uploading 65 FT8 spots to
 * PSK Reporter from inside the RTTY screen. The decision lives here so it is
 * explicit and tested until the FT8 engine is removed outright.
 */
public final class Ft8EnginePolicy {
    private Ft8EnginePolicy() {}

    /** The FT8 engine never runs in RTTYAF. */
    public static boolean runsFt8Engine() {
        return false;
    }
}
