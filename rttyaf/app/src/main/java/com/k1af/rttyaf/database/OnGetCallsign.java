package com.k1af.rttyaf.database;

/**
 * Callback for querying callsigns
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnGetCallsign {
    void  doOnAfterGetCallSign(boolean exists);
}
