package com.gathin.tvhome.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultra-lightweight BLE scanner that detects Apple AirPods when their lid is opened nearby.
 *
 * Uses hardware-level controller filtering for Apple Company ID 0x004C and proximity
 * payload header 0x0719, ensuring near-zero CPU usage on Android TV.
 */
public class AirPodsDetector {
    private static final String TAG = "AirPodsDetector";
    private static final int APPLE_COMPANY_ID = 76; // 0x004C
    private static final int PROXIMITY_RSSI_THRESHOLD = -82; // Living room proximity threshold
    private static final long LID_CLOSE_TIMEOUT_MS = 3500L;

    public interface Listener {
        void onAirPodsLidOpened(AirPodsDevice device);
        void onAirPodsStatusUpdated(AirPodsDevice device);
        void onAirPodsLidClosed(AirPodsDevice device);
    }

    private final Context context;
    private final Handler mainHandler;
    private Listener listener;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private boolean isScanning = false;

    private final Map<String, AirPodsDevice> trackedDevices = new ConcurrentHashMap<>();
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            checkLidTimeouts();
            if (isScanning) {
                watchdogHandler.postDelayed(this, 1500L);
            }
        }
    };

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(TAG, "Bluetooth state changed to ON, starting scan");
                    startScanning();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    stopScanning();
                }
            }
        }
    };

    public AirPodsDetector(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initBluetooth();
        try {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            this.context.registerReceiver(btStateReceiver, filter);
        } catch (Throwable ignored) {}
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void initBluetooth() {
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) {
                bluetoothAdapter = bm.getAdapter();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Bluetooth not available on this device", t);
        }
    }

    public void destroy() {
        stopScanning();
        try {
            context.unregisterReceiver(btStateReceiver);
        } catch (Throwable ignored) {}
    }

    @SuppressLint("MissingPermission")
    public synchronized void startScanning() {
        if (isScanning) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is disabled or null, cannot scan");
            return;
        }

        try {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner == null) {
                Log.w(TAG, "BluetoothLeScanner is null");
                return;
            }

            // Hardware filter: match Apple Inc (76) manufacturer data prefix [0x07, 0x19]
            byte[] manufacturerData = new byte[]{ 0x07, 0x19 };
            byte[] manufacturerDataMask = new byte[]{ (byte) 0xFF, (byte) 0xFF };

            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(APPLE_COMPANY_ID, manufacturerData, manufacturerDataMask)
                    .build();

            // Low-power scan mode optimized for TV wall power and minimal CPU cycles
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();

            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    processScanResult(result);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "BLE Scan failed: " + errorCode);
                    isScanning = false;
                }
            };

            bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            isScanning = true;
            watchdogHandler.postDelayed(watchdogRunnable, 1500L);
            Log.d(TAG, "AirPods BLE scanner active with Apple hardware filter");
        } catch (Throwable t) {
            Log.e(TAG, "Error starting AirPods scan", t);
            isScanning = false;
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void stopScanning() {
        if (!isScanning) return;
        try {
            if (bleScanner != null && scanCallback != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                bleScanner.stopScan(scanCallback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error stopping BLE scan", t);
        } finally {
            isScanning = false;
            scanCallback = null;
            watchdogHandler.removeCallbacks(watchdogRunnable);
            Log.d(TAG, "AirPods BLE scanner stopped");
        }
    }

    private void processScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        ScanRecord record = result.getScanRecord();
        if (record == null) return;

        int rssi = result.getRssi();
        if (rssi < PROXIMITY_RSSI_THRESHOLD) {
            return; // Out of living room proximity range
        }

        byte[] data = record.getManufacturerSpecificData(APPLE_COMPANY_ID);
        if (data == null || data.length < 10) return;

        // Verify Apple proximity message type 0x07 and length 0x19
        if (data[0] != 0x07 || data[1] != 0x19) return;

        String address = result.getDevice().getAddress();
        AirPodsDevice device = trackedDevices.get(address);
        boolean isNew = (device == null);
        if (isNew) {
            device = new AirPodsDevice(address);
            trackedDevices.put(address, device);
        }

        parsePayload(device, data, rssi);

        final AirPodsDevice currentDevice = device;
        final boolean lidJustOpened = !device.lidOpen && ((data[8] >> 3) & 1) == 0;
        final boolean currentLidState = ((data[8] >> 3) & 1) == 0;

        device.lastSeenMs = System.currentTimeMillis();
        boolean previousLidState = device.lidOpen;
        device.lidOpen = currentLidState;

        mainHandler.post(() -> {
            if (listener == null) return;
            if (currentLidState && (!previousLidState || isNew)) {
                Log.d(TAG, "AirPods lid opened: " + currentDevice.modelName + " (" + address + ") RSSI: " + rssi);
                listener.onAirPodsLidOpened(currentDevice);
            } else if (currentLidState) {
                listener.onAirPodsStatusUpdated(currentDevice);
            } else if (!currentLidState && previousLidState) {
                Log.d(TAG, "AirPods lid closed: " + currentDevice.modelName);
                listener.onAirPodsLidClosed(currentDevice);
            }
        });
    }

    private void parsePayload(AirPodsDevice dev, byte[] data, int rssi) {
        int modelId = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        dev.modelId = modelId;
        dev.modelName = AirPodsDevice.resolveModelName(modelId);

        int status = data[5] & 0xFF;
        int podsBattery = data[6] & 0xFF;
        int flagsCase = data[7] & 0xFF;
        int lid = data[8] & 0xFF;
        int colorId = data[9] & 0xFF;

        dev.colorName = AirPodsDevice.resolveColor(colorId);
        dev.rssi = rssi;

        boolean primaryLeft = ((status >> 5) & 0x01) == 1;
        boolean thisInCase = ((status >> 6) & 0x01) == 1;
        boolean xorFactor = primaryLeft ^ thisInCase;

        dev.isLeftInEar = xorFactor ? (status & 0x08) != 0 : (status & 0x02) != 0;
        dev.isRightInEar = xorFactor ? (status & 0x02) != 0 : (status & 0x08) != 0;

        boolean isFlipped = !primaryLeft;
        int leftBatteryNibble = isFlipped ? ((podsBattery >> 4) & 0x0F) : (podsBattery & 0x0F);
        int rightBatteryNibble = isFlipped ? (podsBattery & 0x0F) : ((podsBattery >> 4) & 0x0F);

        int caseBatteryNibble = flagsCase & 0x0F;
        int flags = (flagsCase >> 4) & 0x0F;

        dev.isLeftCharging = isFlipped ? (flags & 0x02) != 0 : (flags & 0x01) != 0;
        dev.isRightCharging = isFlipped ? (flags & 0x01) != 0 : (flags & 0x02) != 0;
        dev.isCaseCharging = (flags & 0x04) != 0;

        dev.leftBattery = decodeBattery(leftBatteryNibble);
        dev.rightBattery = decodeBattery(rightBatteryNibble);
        dev.caseBattery = decodeBattery(caseBatteryNibble);
    }

    private static int decodeBattery(int nibble) {
        if (nibble >= 0 && nibble <= 9) return nibble * 10;
        if (nibble >= 10 && nibble <= 14) return 100;
        return -1; // Disconnected or unknown
    }

    private void checkLidTimeouts() {
        long now = System.currentTimeMillis();
        for (AirPodsDevice dev : trackedDevices.values()) {
            if (dev.lidOpen && (now - dev.lastSeenMs > LID_CLOSE_TIMEOUT_MS)) {
                dev.lidOpen = false;
                Log.d(TAG, "AirPods lid timeout (closed): " + dev.modelName);
                if (listener != null) {
                    listener.onAirPodsLidClosed(dev);
                }
            }
        }
    }
}
