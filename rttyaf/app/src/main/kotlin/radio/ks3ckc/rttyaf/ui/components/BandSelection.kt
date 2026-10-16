package radio.ks3ckc.rttyaf.ui.components

import android.content.Context
import com.k1af.rttyaf.GeneralVariables
import com.k1af.rttyaf.MainViewModel
import com.k1af.rttyaf.R
import com.k1af.rttyaf.database.ControlMode
import com.k1af.rttyaf.database.OperationBand
import com.k1af.rttyaf.rigs.BaseRigOperation

/**
 * Applies a band selection (by index into [OperationBand.bandList]) to the app: updates
 * GeneralVariables, persists the new bandFreq in config, refreshes QSL callsigns, and
 * pushes the new frequency to the rig when CAT/RTS/DTR control is active.
 *
 * Used by the Settings band picker.
 */
fun selectBandIndex(mainViewModel: MainViewModel, context: Context, index: Int) {
    // Never move a keyed rig: Settings stays reachable during an RTTY over,
    // so refuse here (before any state changes) like MainViewModel.qsyTo does.
    if (!mainViewModel.isRetuneAllowed) {
        com.k1af.rttyaf.ui.ToastMessage.show(context.getString(com.k1af.rttyaf.R.string.band_change_refused_transmitting))
        return
    }
    // Compare the meter (wavelength) band, not the index.
    val oldWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    GeneralVariables.bandListIndex = index
    GeneralVariables.band = OperationBand.getBandFreq(index)
    val newWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band)
    mainViewModel.databaseOpr.writeConfig(
        "bandFreq", GeneralVariables.band.toString(), null,
    )
    mainViewModel.databaseOpr.getAllQSLCallsigns()
    // Notify observers (Settings band picker, Operate band label) so the UI updates
    // without waiting for a rig onFreqChanged round-trip.
    GeneralVariables.mutableBandChange.postValue(index)

    // Per-band output level (issue #355): when enabled and this band has a
    // saved level that differs from the current one, restore it and tell the
    // operator where the change came from. Bands with no saved value keep the
    // current (global) level.
    val restoredLevel = radio.ks3ckc.rttyaf.restoredOutputLevelForBand(newWaveLength)
    if (restoredLevel != null) {
        GeneralVariables.volumePercent = restoredLevel / 100f
        GeneralVariables.mutableVolumePercent.postValue(restoredLevel / 100f)
        mainViewModel.databaseOpr.writeConfig("volumeValue", restoredLevel.toString(), null)
        mainViewModel.baseRig?.connector?.setRFVolume(restoredLevel)
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.per_band_volume_restored, restoredLevel, newWaveLength),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    val cm = GeneralVariables.controlMode
    val connected = mainViewModel.isRigConnected()
    android.util.Log.d(
        "FrequencyPicker",
        "bandSelect: index=$index, band=${GeneralVariables.band}, " +
            "controlMode=$cm, rigConnected=$connected",
    )
    try {
        val dir = context.getExternalFilesDir(null)
        if (dir != null) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            java.io.File(dir, "debug.log").appendText(
                "$ts bandSelect: index=$index, band=${GeneralVariables.band}, " +
                    "controlMode=$cm, rigConnected=$connected\n",
            )
        }
    } catch (_: Exception) {
    }

    if (cm == ControlMode.CAT || cm == ControlMode.RTS || cm == ControlMode.DTR) {
        mainViewModel.setOperationBand()
    }
}
