package com.k1af.rttyaf.rigs;

import android.util.Log;

import com.k1af.rttyaf.Ft8Message;
import com.k1af.rttyaf.GeneralVariables;
import com.k1af.rttyaf.connector.X6100Connector;

/**
 * XieGu6100 ft8cns mode, only supports network mode. Ensure proper validation when setting up baseRig.
 */
public class XieGu6100NetRig extends BaseRig {
    private static final String TAG = "x6100RigNet";

    //private final int ctrAddress = 0xE0;//receive address, default 0xE0; rig reply can also be 0x00




    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);
        if (getConnector() != null) {
                getConnector().setPttOn(on);
        }
    }

    @Override
    public boolean isConnected() {
        if (getConnector() == null) {
            return false;
        }
        return getConnector().isConnected();
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            X6100Connector x6100Connector =(X6100Connector)getConnector();
            x6100Connector.getXieguRadio().commandSetMode("u-dig",1);
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            X6100Connector x6100Connector =(X6100Connector)getConnector();
            x6100Connector.getXieguRadio().commandTuneFreq(getFreq());
        }
    }


    @Override
    public void onReceiveData(byte[] data) {
        //command parsing is handled in X6100Radio; no action needed here
    }

    @Override
    public void readFreqFromRig() {//frequency is obtained via X6100Radio status; frequency query command not needed here

    }

    @Override
    public String getName() {
        return "XIEGU X6100 series";
    }

    public XieGu6100NetRig(int civAddress) {
        Log.d(TAG, "x6100RigNet: Create.");
        setCivAddress(civAddress);
    }
}
