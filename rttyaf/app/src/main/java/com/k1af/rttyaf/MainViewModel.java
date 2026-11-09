package com.k1af.rttyaf;
/**
 * -----2022.5.6-----
 * MainViewModel class for decoding FT8 signals and storing decode-related variable data. Lives for the entire app lifecycle.
 * 1. Total decoded count: decoded_counter and mutable_Decoded_Counter.
 * 2. Decoded message list: Messages displayed as Ft8Message, list implemented with ArrayList generic. ft8Messages, mutableFt8MessageList.
 * 3. Both decoding and recording require time synchronization, with UTC time in 15-second cycles. Sync events are triggered by the UtcTimer class.
 * 4. Current UTC time: timerSec, update frequency (heartbeat) determined by UtcTimer, tentatively 100 milliseconds.
 * 5. Get the current MainViewModel instance via the getInstance class method, ensuring a unique instance.
 * 6. Recording implemented with HamAudioRecorder class; currently records to file then reads file data for the decode module. Needs to be changed to direct array method ----TO DO---
 * 7. Decoding uses JNI interface to call native C code. Interface name is rttyaf, maintained by CMakeLists.txt in the cpp folder. Function call interfaces are in decode_ft8.cpp.
 * -----2022.5.9-----
 * If the system is not transmitting, the trigger will initiate recording each cycle. Since starting and stopping recording wastes some time,
 * if the previous recording action is not interrupted, consecutive cycles will have overlapping recording actions, causing the second recording to fail.
 * Therefore, before starting the second cycle's recording, the previous cycle's recording must be stopped, resulting in each recording
 * starting ~300ms after the cycle begins (emulator result). Actual recording length is typically around 14.77 seconds.
 * <p>
 *
 * 2023-08-16 Modified by DS1UFX (based on v0.9), added (tr)uSDX audio over CAT support.
 *
 * @author BG7YOZ
 * @date 2022.8.22
 */

import static com.k1af.rttyaf.GeneralVariables.getStringFromResource;

import com.k1af.rttyaf.concurrent.SafeExecutor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;

import com.k1af.rttyaf.callsign.CallsignDatabase;
import com.k1af.rttyaf.callsign.CallsignInfo;
import com.k1af.rttyaf.callsign.OnAfterQueryCallsignLocation;
import com.k1af.rttyaf.bluetooth.ScoPolicy;
import com.k1af.rttyaf.connector.BluetoothRigConnector;
import com.k1af.rttyaf.connector.CableConnector;
import com.k1af.rttyaf.connector.CableSerialPort;
import com.k1af.rttyaf.connector.ConnectMode;
import com.k1af.rttyaf.connector.FlexConnector;
import com.k1af.rttyaf.connector.IComWifiConnector;
import com.k1af.rttyaf.connector.X6100Connector;
import com.k1af.rttyaf.database.ControlMode;
import com.k1af.rttyaf.database.DatabaseOpr;
import com.k1af.rttyaf.database.OnAfterQueryFollowCallsigns;
import com.k1af.rttyaf.database.OperationBand;
import com.k1af.rttyaf.database.RigNameList;
import com.k1af.rttyaf.flex.FlexRadio;
import com.k1af.rttyaf.flex.RadioTcpClient;
import radio.ks3ckc.rttyaf.rtty.RttyKeyer;
import radio.ks3ckc.rttyaf.rtty.RttyTransmitter;
import radio.ks3ckc.rttyaf.rtty.TuneCarrier;
import com.k1af.rttyaf.ft8transmit.MeterProtectionController;
import com.k1af.rttyaf.ft8transmit.TuneMethod;
import com.k1af.rttyaf.icom.WifiRig;
import com.k1af.rttyaf.log.QSLCallsignRecord;
import com.k1af.rttyaf.log.QSLRecord;
import com.k1af.rttyaf.log.ThirdPartyService;
import com.k1af.rttyaf.rigs.BaseRig;
import com.k1af.rttyaf.rigs.BaseRigOperation;
import com.k1af.rttyaf.rigs.CatConnectionState;
import com.k1af.rttyaf.rigs.CatLiveness;
import com.k1af.rttyaf.rigs.DiscoveryTX500Rig;
import com.k1af.rttyaf.rigs.ElecraftRig;
import com.k1af.rttyaf.rigs.Flex6000Rig;
import com.k1af.rttyaf.rigs.FlexNetworkRig;
import com.k1af.rttyaf.rigs.GuoHeQ900Rig;
import com.k1af.rttyaf.rigs.IcomRig;
import com.k1af.rttyaf.rigs.InstructionSet;
import com.k1af.rttyaf.rigs.KenwoodKT90Rig;
import com.k1af.rttyaf.rigs.KenwoodTS2000Rig;
import com.k1af.rttyaf.rigs.KenwoodTS440Rig;
import com.k1af.rttyaf.rigs.KenwoodTS570Rig;
import com.k1af.rttyaf.rigs.KenwoodTS590Rig;
import com.k1af.rttyaf.rigs.OnRigStateChanged;
import com.k1af.rttyaf.rigs.TrUSDXRig;
import com.k1af.rttyaf.rigs.Wolf_sdr_450Rig;
import com.k1af.rttyaf.rigs.XieGu6100NetRig;
import com.k1af.rttyaf.rigs.XieGu6100Rig;
import com.k1af.rttyaf.rigs.XieGuRig;
import com.k1af.rttyaf.rigs.Yaesu2Rig;
import com.k1af.rttyaf.rigs.Yaesu2_847Rig;
import com.k1af.rttyaf.rigs.Yaesu38Rig;
import com.k1af.rttyaf.rigs.Yaesu38_450Rig;
import com.k1af.rttyaf.rigs.Yaesu39Rig;
import com.k1af.rttyaf.rigs.YaesuDX10Rig;
import com.k1af.rttyaf.timer.OnUtcTimer;
import com.k1af.rttyaf.timer.UtcTimer;
import com.k1af.rttyaf.ui.ToastMessage;
import com.k1af.rttyaf.wave.HamRecorder;
import com.k1af.rttyaf.wave.OnGetVoiceDataDone;
import com.k1af.rttyaf.x6100.X6100Radio;

import radio.ks3ckc.rttyaf.UsbPermissionIntentsKt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainViewModel extends ViewModel {
    String TAG = "rttyaf MainViewModel";
    public final MutableLiveData<Boolean> mutableConfigLoaded = new MutableLiveData<>(false);
    public boolean configIsLoaded = false;

    /** Write debug line to the app's external files debug.log */
    private void fileLog(String msg) {
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx == null) return;
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            new java.io.FileWriter(new java.io.File(dir, "debug.log"), true)
                    .append(ts + " " + msg + "\n").close();
        } catch (Exception ignored) {}
        Log.d(TAG, msg);
    }

    private static MainViewModel viewModel = null;//current existing instance.
    //public static Application application;


    //public int decoded_counter = 0;//total decoded count
    public UtcTimer utcTimer;//timer for sync-triggered actions.


    //public CallsignDatabase callsignDatabase = null;//callsign information database
    public DatabaseOpr databaseOpr;//configuration info and related data database


    // Per-cycle decode state (label overlay + decode count). Synchronized: slot N's
    // late/deep pass and slot N+1's early pass call afterDecode concurrently (#398).
    // Needed-DX alerts: posts sound+vibrate notifications for new DXCC/state CQ stations.
    // Callsign from a tapped Needed-DX notification; the Decode screen observes this to
    // scroll to + highlight that station (pre-select). Set by ComposeMainActivity.
    public MutableLiveData<Long> timerSec = new MutableLiveData<>();//current UTC time. Update frequency determined by UtcTimer, ~100ms when not triggered.
    public MutableLiveData<Boolean> mutableIsRecording = new MutableLiveData<>();//whether currently recording
    public MutableLiveData<Boolean> mutableHamRecordIsRunning = new MutableLiveData<>();//whether HamRecord is running
    public MutableLiveData<Float> mutableTimerOffset = new MutableLiveData<>();//time delay of this cycle
    

    

    

    public MutableLiveData<Boolean> mutableIsFlexRadio = new MutableLiveData<>();//whether it's a Flex radio
    public MutableLiveData<Boolean> mutableIsXieguRadio = new MutableLiveData<>();//whether it's a XieGu radio



    //variables for displaying shared log generation progress
    public MutableLiveData<String> mutableShareInfo=new MutableLiveData<>("");//shared data status
    public MutableLiveData<Integer> mutableSharePosition=new MutableLiveData<>(0);//current position of shared data
    public MutableLiveData<Boolean> mutableShareRunning=new MutableLiveData<>(false);//whether generating shared data
    public MutableLiveData<Integer> mutableShareCount=new MutableLiveData<>(0);//total shared count
    public MutableLiveData<Boolean> mutableImportShareRunning=new MutableLiveData<>(false);//whether importing shared data

    // QSO bottom-sheet persistence (Compose UI). The callsign the sheet is
    // currently bound to (null = no sheet) and whether the user has
    // minimized it (slid down). Lives in the ViewModel so the sheet stays
    // open across navigation away from Decode and back.

    // Decode-screen filter selection (Compose UI). Lives in the ViewModel so
    // the chosen filter survives navigation away from Decode and back, rather
    // than resetting to "All" each time the screen is recreated.


    public HamRecorder hamRecorder;//recording object
    /** RTTY TX: shares the PTT path with FT8/tune and nothing else (see RttyTransmitter). */
    public RttyTransmitter rttyTransmitter;
    /** The tune carrier (steady tone + PTT), gated by the same SWR/route rules as TX. */
    public TuneCarrier tuneCarrier;
    // Deep-pass decodes that arrived mid-TX, replayed to the sequencer after key-up
    public MeterProtectionController meterProtectionController;//ALC auto-volume + SWR halt

    //rig control mode
    public OperationBand operationBand = null;



    public MutableLiveData<ArrayList<CableSerialPort.SerialPort>> mutableSerialPorts = new MutableLiveData<>();
    private ArrayList<CableSerialPort.SerialPort> serialPorts;//serial port list
    public BaseRig baseRig;//rig
    //Observable CAT connection state for the UI status chip. The callbacks below
    //fire off the UI thread, so LiveData is updated via postValue. catConnectionState
    //is a synchronous mirror of the same value: a failed Bluetooth connect calls
    //onRunError() immediately followed by onDisconnected(), and reading LiveData's
    //value across those two posts would race — so the ERROR-preserving guard reads
    //the synchronous field instead.
    public final MutableLiveData<CatConnectionState> mutableCatConnectionState =
            new MutableLiveData<>(CatConnectionState.DISCONNECTED);
    private volatile CatConnectionState catConnectionState = CatConnectionState.DISCONNECTED;

    private void setCatConnectionState(CatConnectionState state) {
        catConnectionState = state;
        mutableCatConnectionState.postValue(state);
    }
    private final OnRigStateChanged onRigStateChanged = new OnRigStateChanged() {
        @Override
        public void onDisconnected() {
            //disconnected from rig. A failed connect fires onRunError() then
            //onDisconnected(); afterDisconnect() preserves ERROR so the chip can
            //stay red until the next connect attempt (onConnecting) or a success.
            stopCatLivenessWatchdog();
            setCatConnectionState(CatConnectionState.afterDisconnect(catConnectionState));
            ToastMessage.show(getStringFromResource(R.string.disconnect_rig));
        }

        @Override
        public void onConnecting() {
            //connection attempt started
            setCatConnectionState(CatConnectionState.CONNECTING);
        }

        @Override
        public void onConnected() {
            //connected to rig
            setCatConnectionState(CatConnectionState.CONNECTED);
            ToastMessage.show(getStringFromResource(R.string.connected_rig));
            // Push the app's current band/frequency to the rig on every connect —
            // including an automatic reconnect, which previously left the rig on
            // whatever frequency it powered up on ("no frequency set after connecting").
            // setOperationBand() no-ops if the rig isn't actually connected and has its
            // own settle delay, so a short post keeps us off the connect-callback thread
            // without racing the link coming up.
            new Handler(Looper.getMainLooper()).postDelayed(MainViewModel.this::setOperationBand, 1500);
            // (Re)start the liveness watchdog for this connection.
            startCatLivenessWatchdog();
        }

        @Override
        public void onPttChanged(boolean isOn) {

        }

        @Override
        public void onRigResponded() {
            // The rig answered with a valid frequency (changed or not) — the liveness signal.
            // onFreqChanged only fires on a change, so it can't be used here (a stable dial
            // would look dead and falsely trip the watchdog).
            markRigResponded();
        }

        @Override
        public void onFreqChanged(long freq) {
            //current frequency: %s
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(freq)));
            //write frequency changes back to global variables
            // Compare the meter (wavelength) band, not the band-list index:
            // OperationBand.getIndexByFreq() appends a new entry for any previously-
            // unseen frequency, so a small in-band dial move changes the index even
            // though the band is the same. Wavelength only changes on a real band hop.
            String oldWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band);
            GeneralVariables.band = freq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            String newWaveLength = BaseRigOperation.getMeterFromFreq(freq);

            databaseOpr.getAllQSLCallsigns();//read out successfully contacted callsigns

        }

        @Override
        public void onRunError(String message) {
            //rig communication error
            stopCatLivenessWatchdog();
            setCatConnectionState(CatConnectionState.ERROR);
            ToastMessage.show(String.format(getStringFromResource(R.string.radio_communication_error)
                    , message));
        }
    };

    // ===== CAT liveness watchdog =====
    // A Bluetooth/serial CAT link can stay "connected" after the radio is powered off (the
    // BT module keeps the socket up), so the chip stayed green and frequency writes went
    // nowhere. This watchdog periodically reads the rig's frequency and, if a previously-
    // responsive rig goes quiet for too long, flips the chip to error. See CatLiveness for
    // the guard logic (never judges during TX; only arms after the first reply, so a rig
    // that doesn't echo frequency reads is never falsely marked dead).
    private static final long CAT_LIVENESS_TICK_MS = 3000;
    private Timer catLivenessTimer;
    private volatile long lastRigResponseMs = 0;
    private volatile boolean sawRigResponseSinceConnect = false;
    // Transmit state observed on the previous tick, used to detect the TX->RX edge so we can
    // re-arm lastRigResponseMs (which freezes during TX) before judging staleness.
    private volatile boolean wasTransmittingLastTick = false;

    /** Record that the rig just demonstrably responded (called from onRigResponded). */
    private void markRigResponded() {
        lastRigResponseMs = System.currentTimeMillis();
        sawRigResponseSinceConnect = true;
    }

    private synchronized void startCatLivenessWatchdog() {
        stopCatLivenessWatchdog();
        lastRigResponseMs = System.currentTimeMillis();
        sawRigResponseSinceConnect = false;
        wasTransmittingLastTick = false;
        catLivenessTimer = new Timer("cat-liveness");
        catLivenessTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                catLivenessTick();
            }
        }, CAT_LIVENESS_TICK_MS, CAT_LIVENESS_TICK_MS);
    }

    private synchronized void stopCatLivenessWatchdog() {
        if (catLivenessTimer != null) {
            catLivenessTimer.cancel();
            catLivenessTimer.purge();
            catLivenessTimer = null;
        }
    }

    /** One watchdog tick: probe the rig, then declare it dead if it's gone quiet too long. */
    private void catLivenessTick() {
        try {
            // Sample the wall clock once per tick so the re-arm and staleness check reason
            // about the same instant — a clock change mid-tick can't skew the comparison.
            long nowMs = System.currentTimeMillis();
            boolean connected = isRigConnected();
            boolean transmitting = (rttyTransmitter != null && rttyTransmitter.isTransmitting())
                    || (tuneCarrier != null && tuneCarrier.isTuning());
            // Actively probe (a frequency read); the reply lands in onRigResponded ->
            // markRigResponded() (onFreqChanged only fires on a change, so a stable dial
            // can't be used). On a dead-but-powered BT module the write succeeds but no
            // reply comes, so the quiet timer below eventually trips.
            if (CatLiveness.shouldProbe(connected, transmitting) && baseRig != null) {
                baseRig.readFreqFromRig();
            }
            // Transmit freezes lastRigResponseMs (we don't probe while keyed). On the TX->RX
            // edge, restart the quiet window from now so the just-sent probe has time to reply
            // before we judge staleness — otherwise a >8s FT8 over falsely trips ERROR.
            if (CatLiveness.shouldRearmAfterTx(wasTransmittingLastTick, transmitting)) {
                lastRigResponseMs = nowMs;
            }
            wasTransmittingLastTick = transmitting;
            if (CatLiveness.isRigStale(connected, transmitting, sawRigResponseSinceConnect,
                    nowMs, lastRigResponseMs, CatLiveness.DEFAULT_TIMEOUT_MS)) {
                stopCatLivenessWatchdog();
                setCatConnectionState(CatConnectionState.ERROR);
                ToastMessage.show(String.format(
                        getStringFromResource(R.string.radio_communication_error),
                        getStringFromResource(R.string.disconnect_rig)));
            }
        } catch (Exception e) {
            // Never let a probe failure crash the timer thread; a real I/O error already
            // surfaces via onRunError(). Log the exception object (not just getMessage(),
            // which can be null) so the stack trace is preserved.
            Log.w(TAG, "cat liveness tick failed", e);
        }
    }

    //message list for signal transmission
    //public ArrayList<Ft8Message> transmitMessages = new ArrayList<>();
    //public MutableLiveData<ArrayList<Ft8Message>> mutableTransmitMessages = new MutableLiveData<>();


    public boolean deNoise = false;//suppress noise in the spectrum

    //*********variables needed for log query********************
    public boolean logListShowCallsign = false;//display format in the log query list
    public String queryKey = "";//query keyword
    public int queryFilter = 0;//filter: 0=all, 1=confirmed, 2=unconfirmed
    public MutableLiveData<Integer> mutableQueryFilter = new MutableLiveData<>();
    public ArrayList<QSLCallsignRecord> callsignRecords = new ArrayList<>();
    //public ArrayList<QSLRecordStr> qslRecords=new ArrayList<>();
    //********************************************
    //followed callsign list
    //public ArrayList<String> followCallsign = new ArrayList<>();


    //log management HTTP SERVER

    /**
     * Get the MainViewModel instance, ensuring a unique instance exists throughout the entire app lifecycle.
     *
     * @param owner ViewModelStoreOwner owner, typically an Activity or Fragment.
     * @return MainViewModel Returns a MainViewModel instance.
     */
    public static MainViewModel getInstance(ViewModelStoreOwner owner) {
        if (viewModel == null) {
            viewModel = new ViewModelProvider(owner).get(MainViewModel.class);
        }
        return viewModel;
    }

    

    

    /**
     * MainViewModel constructor accomplishes the following:
     * 1. Create a UTC-synchronized clock. The clock is UtcTimer class, internally implemented with Timer and TimerTask. Callbacks are multi-threaded; thread safety must be considered.
     * 2. Create Mutable-type decoded message list.
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public MainViewModel() {

        //get configuration info.
        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext()
                , "data.db");
        //create recording object
        hamRecorder = new HamRecorder(null);
        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

        //create timer for displaying time — a 1-second (1000ms) tick, just to refresh the clock.
        utcTimer = new UtcTimer(1000, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {//clock info when not triggered

            }

            @Override
            public void doOnSecTimer(long utc) {//triggered at specified interval
                timerSec.postValue(utc);//send current UTC time
                mutableIsRecording.postValue(hamRecorder.isRunning());
                mutableHamRecordIsRunning.postValue(hamRecorder.isRunning());//send current timer state
            }
        });
        utcTimer.start();//start timer

        //synchronize time. Microsoft's NTP server
        UtcTimer.syncTime(null);




        // PTT for RTTY TX and the tune carrier through the configured control
        // path (CAT/RTS/DTR; VOX rigs key on the audio itself), pausing Bluetooth
        // SCO around the over where the control path needs it.
        final RttyKeyer keyer = new RttyKeyer() {
            private boolean needControlSco() {
                return ScoPolicy.needControlSco(GeneralVariables.connectMode,
                        GeneralVariables.controlMode,
                        baseRig != null,
                        baseRig != null && baseRig.supportWaveOverCAT());
            }

            private boolean controlPathKeys() {
                return GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR;
            }

            @Override
            public void keyDown() {
                if (controlPathKeys() && baseRig != null) {
                    if (needControlSco()) stopSco();
                    baseRig.setPTT(true);
                }
            }

            @Override
            public void keyUp() {
                if (controlPathKeys() && baseRig != null) {
                    baseRig.setPTT(false);
                    if (needControlSco()) startSco();
                }
            }
        };

        meterProtectionController = new MeterProtectionController();
        tuneCarrier = new TuneCarrier(keyer,
                () -> rttyTransmitter != null && rttyTransmitter.isTransmitting(),
                () -> meterProtectionController.isSwrLocked(),
                this::catCarriesAudio);
        rttyTransmitter = new RttyTransmitter(keyer,
                () -> tuneCarrier.isTuning(),
                this::catCarriesAudio,
                () -> meterProtectionController.isSwrLocked(),
                () -> {
                    meterProtectionController.onTxCycleEnd();
                    return kotlin.Unit.INSTANCE;
                });
        // An SWR halt takes whatever is on the air off it.
        meterProtectionController.setOnSwrHalt(() -> {
            rttyTransmitter.stop();
            tuneCarrier.stop();
        });
    }

    /** (tr)uSDX-style rigs carry the TX audio over the CAT link; RTTY has no sender for that. */
    private boolean catCarriesAudio() {
        return GeneralVariables.controlMode == ControlMode.CAT
                && baseRig != null && baseRig.isConnected() && baseRig.supportWaveOverCAT();
    }

    

    

    

    

    

    /**
     * Whether CAT may move the dial / mode right now: never while an FT8 or RTTY
     * over is keyed, so a Settings band pick can't retune a transmitting rig.
     * Pure decision logic, unit-tested without the Android runtime.
     */
    public static boolean retuneAllowed(boolean ft8Transmitting, boolean rttyTransmitting) {
        return !ft8Transmitting && !rttyTransmitting;
    }

    /** {@link #retuneAllowed} evaluated against the live transmitter state. */
    public boolean isRetuneAllowed() {
        return rttyTransmitter == null
                || retuneAllowed(tuneCarrier != null && tuneCarrier.isTuning(), rttyTransmitter.isTransmitting());
    }

    

    

    

    /**
     * Delete a single file
     *
     * @param fileName the filename of the file to delete
     */
    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        // If the file at the given path exists and is a file, delete it directly
        if (file.exists() && file.isFile()) {
            file.delete();
        }
    }

    

    

    

    // ===== FT8 DXpedition Hound mode =====

    

    

    

    /**
     * Handle a TUNE tap according to the tune-method setting (issue #425).
     * Returns true when the tap was handled here — either the rig's internal
     * ATU was started (it keys its own carrier and stops by itself) or the
     * operator chose Internal but no capable rig is connected (toast, no
     * surprise carrier). Returns false when the caller should run the normal
     * low-power carrier tune.
     */
    public boolean tryStartTuneViaAtu() {
        boolean atuAvailable = baseRig != null && baseRig.isConnected()
                && baseRig.supportsAtuTune() && !baseRig.isPttOn();
        int action = TuneMethod.decide(GeneralVariables.tuneMethod, atuAvailable);
        if (action == TuneMethod.ACTION_RIG_ATU) {
            fileLog("tune: starting rig ATU (method=" + GeneralVariables.tuneMethod
                    + ", rig=" + baseRig.getName() + ")");
            baseRig.startAtuTune();
            ToastMessage.show(getStringFromResource(R.string.tune_atu_started));
            return true;
        }
        if (action == TuneMethod.ACTION_UNAVAILABLE) {
            fileLog("tune: method=INTERNAL but no ATU-capable rig connected");
            ToastMessage.show(getStringFromResource(R.string.tune_atu_unavailable));
            return true;
        }
        return false;
    }

    /**
     * Set the operating carrier frequency. Only operates if the rig is connected.
     */
    public void setOperationBand() {
        if (!isRigConnected()) {
            fileLog("setOperationBand: rig not connected, skipping");
            return;
        }
        if (!isRetuneAllowed()) {
            fileLog("setOperationBand: refused, transmitting (freq=" + GeneralVariables.band + ")");
            return;
        }

        fileLog("setOperationBand: sending USB mode, then freq=" + GeneralVariables.band
                + " in 800ms (controlMode=" + GeneralVariables.controlMode + ")");
        //set USB mode first, then set frequency
        baseRig.setUsbModeToRig();//set USB mode

        //delay 1 second before sending the second command to prevent XieGu X6100 disconnection issues
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // Re-check: an over may have started during the 800 ms delay.
                if (!isRetuneAllowed()) {
                    fileLog("setOperationBand: delayed freq write refused, transmitting");
                    return;
                }
                fileLog("setOperationBand: setting freq=" + GeneralVariables.band
                        + " (rig.getFreq=" + baseRig.getFreq() + ")");
                baseRig.setFreq(GeneralVariables.band);//set frequency
                baseRig.setFreqToRig();
            }
        }, 800);
    }

    /**
     * Move the dial to an arbitrary frequency (RTTY spot "go"): record it as the
     * current band/dial, persist it, notify band observers and push it to the rig
     * over CAT. Crossing an amateur band is allowed here only because the RTTY
     * spot flow asks the operator first (antenna/ATU safety); same-band moves
     * need no confirmation. Refused while an FT8 or RTTY over is on the air —
     * like {@link #setOperatingMode} — so CAT never retunes a keyed rig.
     *
     * @return true if the dial was moved; false if rejected (non-positive
     *         frequency, or transmitting)
     */
    public boolean qsyTo(long freq) {
        if (freq <= 0) return false;
        if (!isRetuneAllowed()) {
            fileLog("qsyTo: refused, transmitting (freq=" + freq + ")");
            return false;
        }
        fileLog("qsyTo: " + freq);
        GeneralVariables.band = freq;
        GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
        if (databaseOpr != null) {
            databaseOpr.writeConfig("bandFreq", String.valueOf(freq), null);
        }
        GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
        setOperationBand();
        return true;
    }

    

    

    public void setCivAddress() {
        if (baseRig != null) {
            baseRig.setCivAddress(GeneralVariables.civAddress);
        }
    }

    public void setControlMode() {
        if (baseRig != null) {
            baseRig.setControlMode(GeneralVariables.controlMode);
        }
        //Notify observers (CAT status chip visibility) of the mode change.
        GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
    }


    /**
     * Connect to rig via USB
     *
     * @param context context
     * @param port    serial port
     */
    public void connectCableRig(Context context, CableSerialPort.SerialPort port) {
        if (GeneralVariables.controlMode == ControlMode.VOX) {//if currently VOX, switch to CAT mode
            GeneralVariables.controlMode = ControlMode.CAT;
            databaseOpr.writeConfig("ctrMode", String.valueOf(ControlMode.CAT), null);
            GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
        }
        connectRig();

        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        CableConnector connector = new CableConnector(context, port, GeneralVariables.baudRate
                //, GeneralVariables.controlMode);
                , GeneralVariables.controlMode,baseRig);

        //2023-08-16 Modified by DS1UFX (based on v0.9), for (tr)uSDX audio over CAT support.
        connector.setOnCableDataReceived(new CableConnector.OnCableDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                Log.i(TAG, "call hamRecorder.doOnWaveDataReceived");
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        connector.connect();

        //delay 1 second before setting mode, to prevent some rigs from not responding in time
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 1000);

    }

    public void connectBluetoothRig(Context context, BluetoothDevice device) {
        // Remember this device so the SPP/CAT link can auto-reconnect on the next app launch
        // (issue #223). Centralized here so every entry point -- the Compose picker, the legacy
        // SelectBluetoothDialog, and the startup auto-connect path -- persists consistently.
        if (!device.getAddress().equals(GeneralVariables.bluetoothDeviceAddress)) {
            GeneralVariables.bluetoothDeviceAddress = device.getAddress();
            databaseOpr.writeConfig("bluetoothDeviceAddress", device.getAddress(), null);
        }

        GeneralVariables.controlMode = ControlMode.CAT;//Bluetooth control mode, only CAT control is supported
        GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
        connectRig();
        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        BluetoothRigConnector connector = BluetoothRigConnector.getInstance(context, device.getAddress()
                , GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);

        // Route HFP audio over SCO as soon as the CAT rig is wired -- but only when a Bluetooth
        // audio profile (headset/A2DP) is actually connected. SPP-only CAT adapters have no audio
        // path, so forcing SCO + headset mode on them would be wrong and disruptive (PR #227
        // review). Without this, SCO is only (re)started as a side effect of the TX/RX cycle
        // (needControlSco()/startSco()), which never fires until a rig is connected -- so on
        // relaunch audio was dead in both directions until the user manually reconfigured
        // Bluetooth (issue #223). The BluetoothStateBroadcastReceive path remains the fallback
        // for devices whose audio profile connects after this point.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isBTConnected()) {
                setBlueToothOn();
            }
        });

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 5000);
    }

    /**
     * Connect to ICOM or XieGu X6100 series rig via network
     * @param wifiRig ICom or XieGu WiFi mode rig
     */
    public void connectWifiRig(WifiRig wifiRig) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }

        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        //currently Icom and XieGu X6100 share the same connector
        IComWifiConnector iComWifiConnector = new IComWifiConnector(GeneralVariables.controlMode
                ,wifiRig);
        iComWifiConnector.setOnWifiDataReceived(new IComWifiConnector.OnWifiDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }

            @Override
            public void OnCivReceived(byte[] data) {

            }
        });

        iComWifiConnector.connect();
        connectRig();//assign baseRig

        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 1000);
    }

    /**
     * Connect to FlexRadio
     *
     * @param context   context
     * @param flexRadio FlexRadio object
     */
    public void connectFlexRadioRig(Context context, FlexRadio flexRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        FlexConnector flexConnector = new FlexConnector(context, flexRadio, GeneralVariables.controlMode);
        flexConnector.setOnWaveDataReceived(new FlexConnector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        flexConnector.connect();
        connectRig();

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(flexConnector);
//
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 3000);
    }

    /**
     * Connect to XieGu Radio
     *
     * @param context   context
     * @param xieguRadio X6100Radio object
     */
    public void connectXieguRadioRig(Context context, X6100Radio xieguRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        X6100Connector xieguConnector = new X6100Connector(context, xieguRadio, GeneralVariables.controlMode);
        xieguConnector.setOnWaveDataReceived(new X6100Connector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                //Log.e(TAG,String.format("data len:%d",bufferLen));
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });


        xieguConnector.connect();
        connectRig();
        xieguConnector.setBaseRig(baseRig);
        //receive data sent back from the rig
        xieguRadio.setOnReceiveDataListener(new X6100Radio.OnReceiveDataListener() {
            @Override
            public void onDataReceive(byte[] data) {
                baseRig.onReceiveData(data);
            }
        });


        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(xieguConnector);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 3000);
    }


    /**
     * Create different rig models based on the instruction set
     */
    private void connectRig() {

        if (baseRig != null) {
            baseRig.onDisconnecting();
        }
        baseRig = null;
        //determine the rig type: ICOM, YAESU 2, YAESU 3
        switch (GeneralVariables.instructionSet) {
            case InstructionSet.ICOM:
                baseRig = new IcomRig(GeneralVariables.civAddress,true);
                break;
            case InstructionSet.ICOM_756:
                baseRig = new IcomRig(GeneralVariables.civAddress,false);
                break;
            case InstructionSet.YAESU_2:
                baseRig = new Yaesu2Rig();
                break;
            case InstructionSet.YAESU_847:
                baseRig = new Yaesu2_847Rig();
                break;
            case InstructionSet.YAESU_3_9:
                baseRig = new Yaesu39Rig(false);//Yaesu 3rd-gen commands, 9-digit frequency, USB mode
                break;
            case InstructionSet.YAESU_3_9_U_DIG:
                baseRig = new Yaesu39Rig(true);//Yaesu 3rd-gen commands, 9-digit frequency, DATA-USB mode
                break;
            case InstructionSet.YAESU_3_8:
                baseRig = new Yaesu38Rig();//Yaesu 3rd-gen commands, 8-digit frequency
                break;
            case InstructionSet.YAESU_3_450:
                baseRig = new Yaesu38_450Rig();//Yaesu 3rd-gen commands, 8-digit frequency
                break;
            case InstructionSet.KENWOOD_TK90:
                baseRig = new KenwoodKT90Rig();//Kenwood TK90
                break;
            case InstructionSet.YAESU_DX10:
                baseRig = new YaesuDX10Rig();//YAESU DX10 DX101
                break;
            case InstructionSet.YAESU_FT710:
                baseRig = new YaesuDX10Rig();//FT-710 reuses DX10 CAT; FT-710-specific fix is in CableSerialPort
                break;
            case InstructionSet.KENWOOD_TS590:
                baseRig = new KenwoodTS590Rig();//KENWOOD TS590
                break;
            case InstructionSet.GUOHE_Q900:
                baseRig = new GuoHeQ900Rig();//GuoHe Q900
                break;
            case InstructionSet.XIEGUG90S://XieGu, USB mode
                baseRig = new XieGuRig(GeneralVariables.civAddress);//XieGu G90S
                break;
            case InstructionSet.ELECRAFT:
                baseRig = new ElecraftRig();//ELECRAFT
                break;
            case InstructionSet.FLEX_CABLE:
                baseRig = new Flex6000Rig();//FLEX6000
                break;
            case InstructionSet.FLEX_NETWORK:
                baseRig = new FlexNetworkRig();
                break;
            case InstructionSet.XIEGU_6100_FT8CNS:
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {//only works in network mode
                    baseRig = new XieGu6100NetRig(GeneralVariables.civAddress);//XieGu 6100 FT8CNS mode
                }else{//otherwise use legacy mode
                    baseRig = new XieGu6100Rig(GeneralVariables.civAddress);//XieGu 6100
                }
                break;
            case InstructionSet.XIEGU_6100:
                baseRig = new XieGu6100Rig(GeneralVariables.civAddress);//XieGu 6100
                break;
            case InstructionSet.KENWOOD_TS2000:
                baseRig = new KenwoodTS2000Rig();//Kenwood TS2000
                break;
            case InstructionSet.DISCOVERY_TX500:
                baseRig = new DiscoveryTX500Rig();//Lab599 Discovery TX-500 (TS-2000 + DATA mode)
                break;
            case InstructionSet.WOLF_SDR_DIGU:
                baseRig = new Wolf_sdr_450Rig(false);
                break;
            case InstructionSet.WOLF_SDR_USB:
                baseRig = new Wolf_sdr_450Rig(true);
                break;
            case InstructionSet.TRUSDX:
                baseRig = new TrUSDXRig();//(tr)uSDX
                break;
            case InstructionSet.KENWOOD_TS570:
                baseRig = new KenwoodTS570Rig();//KENWOOD TS-570D
                break;
            case InstructionSet.KENWOOD_TS440:
                baseRig = new KenwoodTS440Rig();//KENWOOD TS-440S (TS-570 CAT, USB mode)
                break;
        }

        // Store the rig name for PSKReporter software string.
        // Use the user-selected model name from RigNameList (same source as the
        // Settings rig picker) rather than the Java class name, which can
        // differ (e.g. YaesuDX10Rig for FT-710).
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx != null) {
                RigNameList rigList = RigNameList.getInstance(ctx);
                GeneralVariables.myRigName =
                        rigList.getRigNameByIndex(GeneralVariables.modelNo).modelName;
            }
        } catch (Exception e) {
            GeneralVariables.myRigName = "";
        }

        if ((GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK)
                || ((GeneralVariables.instructionSet == InstructionSet.ICOM
                || GeneralVariables.instructionSet==InstructionSet.XIEGU_6100
                || GeneralVariables.instructionSet==InstructionSet.XIEGU_6100_FT8CNS)
                && GeneralVariables.connectMode == ConnectMode.NETWORK)) {
            hamRecorder.setDataFromLan();
        } else {
            if (GeneralVariables.controlMode != ControlMode.CAT || baseRig == null
                    || !baseRig.supportWaveOverCAT()) {
                hamRecorder.setDataFromMic();
            } else {
                hamRecorder.setDataFromLan();
            }
        }

        // Wire meter data callback for ALC auto-volume + SWR halt
        if (baseRig != null && meterProtectionController != null) {
            baseRig.setOnMeterData(new BaseRig.OnMeterData() {
                @Override
                public void onMeterUpdate(int normalizedAlc, int normalizedSwr) {
                    meterProtectionController.onMeterUpdate(normalizedAlc, normalizedSwr);
                }
            });
        }

        mutableIsFlexRadio.postValue(GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK);
        mutableIsXieguRadio.postValue(GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS);

    }


    /**
     * Reinitialize the audio input to pick up newly connected USB audio devices.
     * Call this after a USB device attach event to switch to USB audio if available.
     */
    public void reinitializeAudioInput() {
        if (hamRecorder != null) {
            hamRecorder.reinitializeMicRecorder();
        }
    }

    /**
     * RECORD_AUDIO was just granted: bring up the mic capture that could not
     * open at startup without it (see {@link HamRecorder#restartMicAfterPermissionGrant}).
     * On a plain daemon thread, not the caller's: the grant arrives on the main
     * thread, and the reinit joins the old capture thread (up to a second) and
     * can wait on another reinit holding the MicRecorder lock.
     */
    public void restartAudioAfterMicGrant() {
        final HamRecorder recorder = hamRecorder;
        if (recorder == null) return;
        GeneralVariables.fileLog("RECORD_AUDIO granted: restarting mic capture");
        Thread restart = new Thread(recorder::restartMicAfterPermissionGrant, "MicGrantRestart");
        restart.setDaemon(true);
        restart.start();
    }

    /**
     * Check whether the rig is connected. Two cases: rigBaseClass not created, or serial port connection failed.
     *
     * @return whether connected
     */
    public boolean isRigConnected() {
        if (baseRig == null) {
            return false;
        } else {
            return baseRig.isConnected();
        }
    }

    /**
     * Re-trigger the current rig's CAT connection. Backs the tap-to-reconnect
     * status chip: Bluetooth often only connects on the second attempt, so this
     * reuses the connector's existing connect() path (which, for Bluetooth, runs
     * socketConnect() again). No-op when no rig/connector is configured.
     */
    public void reconnectRig() {
        if (baseRig == null || baseRig.getConnector() == null) {
            return;
        }
        setCatConnectionState(CatConnectionState.CONNECTING);
        baseRig.getConnector().connect();
    }

    /**
     * Get the serial port device list
     */
    public void getUsbDevice() {
        serialPorts =
                CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }


    public void startSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();//71ms
        audioManager.setSpeakerphoneOn(false);//enter headset mode
    }

    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);//exit headset mode
        }

    }


    public void setBlueToothOn() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
        }

        /*
        MODE_NORMAL corresponds to music playback. For speaker output, call audioManager.setSpeakerphoneOn(true).
        For headset or earpiece, set mode to MODE_IN_CALL (pre-3.0) or MODE_IN_COMMUNICATION (3.0+).
         */
        audioManager.setMode(AudioManager.MODE_NORMAL);//178ms
        audioManager.setBluetoothScoOn(true);
        audioManager.stopBluetoothSco();
        audioManager.startBluetoothSco();//71ms
        audioManager.setSpeakerphoneOn(false);//enter headset mode

        //entering Bluetooth headset mode
        ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));

    }

    public void setBlueToothOff() {

        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);//exit headset mode
        }
        //leaving Bluetooth headset mode
        ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));

    }


    /**
     * Check whether Bluetooth is connected
     *
     * @return whether connected
     */
    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        // On Android 12+, getProfileConnectionState requires BLUETOOTH_CONNECT.
        // ComposeMainActivity.onCreate asks for it asynchronously, so on the first launch this
        // method may run before the user has answered the prompt — return false rather than crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Context ctx = GeneralVariables.getMainContext();
            if (ctx == null
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;

        try {
            //Bluetooth headset, supports voice input and output
            int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
            int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
        } catch (SecurityException se) {
            return false;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // The liveness watchdog otherwise only stops on disconnect/error/stale; if the
        // ViewModel is cleared while still "connected" the Timer thread would keep probing
        // the rig indefinitely. Tear it down here too.
        stopCatLivenessWatchdog();
    }

    private static final String ACTION_USB_AUDIO_PERMISSION =
            "com.k1af.rttyaf.USB_AUDIO_PERMISSION";

    /**
     * Request USB audio device permission if not already granted.
     * Mirrors ConfigFragment.requestUsbPermissionIfNeeded().
     */
    public void requestUsbPermissionIfNeeded(UsbDevice device) {
        if (device == null) return;
        Context context = GeneralVariables.getMainContext();
        if (context == null) return;
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB audio device already has permission");
            return;
        }

        Log.d(TAG, "Requesting USB permission for audio device: " + device.getProductName());
        PendingIntent permissionIntent = UsbPermissionIntentsKt.createUsbPermissionIntent(
                context, ACTION_USB_AUDIO_PERMISSION);

        BroadcastReceiver permReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_USB_AUDIO_PERMISSION.equals(intent.getAction())) {
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    Log.d(TAG, "USB audio permission " + (granted ? "granted" : "denied"));
                    GeneralVariables.fileLog("USB audio permission "
                            + (granted ? "granted" : "denied"));
                    try { ctx.unregisterReceiver(this); } catch (Exception ignored) {}
                    if (granted) {
                        // The recorder was constructed before this permission
                        // existed and fell back to the built-in mic (see
                        // MicRecorder.openUsbAudioInput's hasPermission check).
                        // Now that we're allowed to open the device, rebind the
                        // input so RX actually uses the USB sound card. TX is
                        // immune to this bug because it opens the device lazily
                        // at transmit time, always after this grant.
                        reinitializeAudioInput();
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION));
        }
        usbManager.requestPermission(device, permissionIntent);
    }

}