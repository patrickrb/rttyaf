package com.k1af.rttyaf.rigs;

/**
 * Callback for data received from the rig
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnConnectReceiveData {
    void onData(byte[] data);
}
