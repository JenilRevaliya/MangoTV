package com.gathin.tvhome.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import java.util.Set;
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
    private static final int PROXIMITY_RSSI_THRESHOLD = -90; // Living room proximity threshold
    private static final long LID_CLOSE_TIMEOUT_MS = 4000L;

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

            // TV Bluetooth controllers often lack hardware APCF filter slots (mMaxScanFilters: 0).
            // Pass null filters so Android delegates scan results to software filter in processScanResult.
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
                public void onBatchScanResults(java.util.List<ScanResult> results) {
                    if (results != null) {
                        for (ScanResult r : results) {
                            processScanResult(r);
                        }
                    }
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "BLE Scan failed: " + errorCode);
                    isScanning = false;
                }
            };

            bleScanner.startScan(null, settings, scanCallback);
            isScanning = true;
            watchdogHandler.postDelayed(watchdogRunnable, 1500L);
            Log.i(TAG, "AirPods BLE scanner active with zero-filter software parsing");
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

        byte[] data = record.getManufacturerSpecificData(APPLE_COMPANY_ID);
        if (data == null || data.length < 5) return;

        // Verify Apple proximity message type 0x07 (AirPods proximity pairing)
        if (data[0] != 0x07) return;

        int rssi = result.getRssi();
        if (rssi < PROXIMITY_RSSI_THRESHOLD) {
            return; // Out of living room proximity range
        }

        String address = result.getDevice().getAddress();
        AirPodsDevice device = trackedDevices.get(address);
        boolean isNew = (device == null);
        if (isNew) {
            device = new AirPodsDevice(address);
            trackedDevices.put(address, device);
        }

        parsePayload(device, data, rssi);

        final AirPodsDevice currentDevice = device;
        device.lastSeenMs = System.currentTimeMillis();
        boolean previousLidState = device.lidOpen;
        // Apple AirPods case ONLY transmits type 0x07 proximity packets while lid is open.
        device.lidOpen = true;

        mainHandler.post(() -> {
            if (listener == null) return;
            if (!previousLidState || isNew) {
                Log.i(TAG, "AirPods lid opened: " + currentDevice.modelName + " (" + address + ") RSSI: " + rssi);
                listener.onAirPodsLidOpened(currentDevice);
            } else {
                listener.onAirPodsStatusUpdated(currentDevice);
            }
        });
    }

    private void parsePayload(AirPodsDevice dev, byte[] data, int rssi) {
        if (data == null || data.length < 5) return;
        int prefix = data[2] & 0xFF;
        int modelId = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        dev.modelId = modelId;
        if (!dev.isCustomName) {
            dev.modelName = AirPodsDevice.resolveModelName(modelId);
        }
        dev.rssi = rssi;

        if (prefix == 0x00) {
            // Pairing / Setup Mode Beacon (Length ~15-17 bytes)
            // When AirPods are in pairing mode (white LED blinking on case),
            // Apple broadcasts prefix 0x00 where Bytes 5-10 contain the 6-byte Classic Bluetooth MAC address!
            if (data.length >= 11) {
                String classicMac = String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                        data[5] & 0xFF, data[6] & 0xFF, data[7] & 0xFF,
                        data[8] & 0xFF, data[9] & 0xFF, data[10] & 0xFF);
                dev.classicAddress = classicMac;
                Log.i(TAG, "Extracted Classic Bluetooth MAC from pairing beacon: " + classicMac);
            }
            // Setup packet does not carry battery telemetry
            dev.leftBattery = -1;
            dev.rightBattery = -1;
            dev.caseBattery = -1;
        } else if (prefix == 0x01 && data.length >= 8) {
            // Paired Mode Proximity Beacon (Length 25-27 bytes)
            // Telemetry: byte 5 = status, byte 6 = pods battery, byte 7 = flags/case
            int status = data[5] & 0xFF;
            int podsBattery = data[6] & 0xFF;
            int flagsCase = data[7] & 0xFF;
            int colorId = (data.length > 9) ? (data[9] & 0xFF) : 0;

            dev.colorName = AirPodsDevice.resolveColor(colorId);

            // OpenPods & Apple Continuity proximity specification:
            // Bit 5 of status (byte 5) determines whether left & right earbuds are flipped
            boolean isFlipped = (status & 0x20) == 0;

            // When isFlipped is true: Left is upper nibble, Right is lower nibble.
            // When isFlipped is false: Left is lower nibble, Right is upper nibble.
            int leftBatteryNibble = isFlipped ? ((podsBattery >> 4) & 0x0F) : (podsBattery & 0x0F);
            int rightBatteryNibble = isFlipped ? (podsBattery & 0x0F) : ((podsBattery >> 4) & 0x0F);

            int caseBatteryNibble = flagsCase & 0x0F;
            int flags = (flagsCase >> 4) & 0x0F;

            // Charging flags
            dev.isLeftCharging = (flags & (isFlipped ? 0x02 : 0x01)) != 0;
            dev.isRightCharging = (flags & (isFlipped ? 0x01 : 0x02)) != 0;
            dev.isCaseCharging = (flags & 0x04) != 0;

            // In-ear status
            dev.isLeftInEar = (status & (isFlipped ? 0x08 : 0x02)) != 0;
            dev.isRightInEar = (status & (isFlipped ? 0x02 : 0x08)) != 0;

            dev.leftBattery = decodeBattery(leftBatteryNibble);
            dev.rightBattery = decodeBattery(rightBatteryNibble);
            dev.caseBattery = decodeBattery(caseBatteryNibble);
        }

        // Resolve friendly/custom name from bonded cache or remote Bluetooth device
        resolveDeviceName(dev);

        Log.d(TAG, "AirPods packet [" + bytesToHex(data) + "] L:" + dev.leftBattery + "% R:" + dev.rightBattery + "% C:" + dev.caseBattery + "% name=" + dev.getDisplayName());
    }

    private static int decodeBattery(int nibble) {
        if (nibble >= 0 && nibble <= 10) return nibble * 10;
        return -1; // 15 is disconnected/absent; >10 is invalid
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    public void resolveDeviceName(AirPodsDevice dev) {
        if (dev == null) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                // Check direct address match
                for (BluetoothDevice bDev : bonded) {
                    if (bDev.getAddress() != null && bDev.getAddress().equalsIgnoreCase(dev.address)) {
                        String name = bDev.getName();
                        if (name != null && !name.trim().isEmpty()) {
                            dev.modelName = name.trim();
                            dev.classicAddress = bDev.getAddress();
                            dev.isCustomName = true;
                            return;
                        }
                    }
                }
                // Check if any bonded device has classic address or name matches AirPods/Beats
                if (!dev.isCustomName) {
                    for (BluetoothDevice bDev : bonded) {
                        String name = bDev.getName();
                        if (name != null && !name.trim().isEmpty()) {
                            String lower = name.toLowerCase();
                            if (lower.contains("airpod") || lower.contains("beats") || lower.contains("buds")) {
                                dev.modelName = name.trim();
                                dev.classicAddress = bDev.getAddress();
                                dev.isCustomName = true;
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Check remote device cached name
        try {
            BluetoothDevice remote = bluetoothAdapter.getRemoteDevice(dev.address);
            if (remote != null) {
                String name = remote.getName();
                if (name != null && !name.trim().isEmpty()) {
                    dev.modelName = name.trim();
                    dev.isCustomName = true;
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // If still generic, initiate brief classic inquiry to capture EIR name
        if (!dev.isCustomName) {
            startClassicDiscoveryForName(dev);
        }
    }

    private void startClassicDiscoveryForName(final AirPodsDevice dev) {
        if (dev == null || dev.isCustomName || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (bluetoothAdapter.isDiscovering()) return;

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                        BluetoothDevice foundDev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        String foundName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                        if (foundName == null && foundDev != null) {
                            try {
                                foundName = foundDev.getName();
                            } catch (SecurityException ignored) {}
                        }
                        if (foundName != null && !foundName.trim().isEmpty()) {
                            String lower = foundName.toLowerCase();
                            if (lower.contains("airpod") || lower.contains("beats") || lower.contains("buds")) {
                                dev.modelName = foundName.trim();
                                if (foundDev != null) {
                                    dev.classicAddress = foundDev.getAddress();
                                }
                                dev.isCustomName = true;
                                Log.i(TAG, "Discovered custom AirPods name: " + dev.modelName);
                                try {
                                    bluetoothAdapter.cancelDiscovery();
                                    context.unregisterReceiver(this);
                                } catch (Throwable ignored) {}
                                mainHandler.post(() -> {
                                    if (listener != null) listener.onAirPodsStatusUpdated(dev);
                                });
                            }
                        }
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                        try {
                            context.unregisterReceiver(this);
                        } catch (Throwable ignored) {}
                    }
                }
            };

            context.registerReceiver(rx, filter);
            bluetoothAdapter.startDiscovery();
            mainHandler.postDelayed(() -> {
                try {
                    if (bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }
                    context.unregisterReceiver(rx);
                } catch (Throwable ignored) {}
            }, 6000L);
        } catch (Throwable t) {
            Log.w(TAG, "Could not start classic discovery for name", t);
        }
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
