package com.gathin.tvhome.settings.connectivity;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * Real Android System Connectivity Inspector and Native Settings Launcher.
 * Safely inspects actual Wi-Fi and Bluetooth state and provides compliant intents.
 */
public final class SystemConnectivityHelper {

    public static class WifiState {
        public final boolean isConnected;
        public final boolean isAvailable;
        public final String ssid;
        public final String ipAddress;
        public final int rssi;
        public final String statusSummary;

        public WifiState(boolean isConnected, boolean isAvailable, String ssid, String ipAddress, int rssi, String statusSummary) {
            this.isConnected = isConnected;
            this.isAvailable = isAvailable;
            this.ssid = ssid;
            this.ipAddress = ipAddress;
            this.rssi = rssi;
            this.statusSummary = statusSummary;
        }
    }

    public static class BtState {
        public final boolean isEnabled;
        public final boolean isAvailable;
        public final int pairedCount;
        public final List<String> connectedDeviceNames;
        public final String statusSummary;

        public BtState(boolean isEnabled, boolean isAvailable, int pairedCount, List<String> connectedDeviceNames, String statusSummary) {
            this.isEnabled = isEnabled;
            this.isAvailable = isAvailable;
            this.pairedCount = pairedCount;
            this.connectedDeviceNames = connectedDeviceNames != null ? connectedDeviceNames : Collections.emptyList();
            this.statusSummary = statusSummary;
        }
    }

    public static WifiState getWifiState(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (cm == null || wm == null) {
            return new WifiState(false, false, null, "127.0.0.1", -100, "Unavailable");
        }

        boolean isWifiConnected = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    isWifiConnected = true;
                }
            }
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                isWifiConnected = true;
            }
        }

        String ssid = null;
        int rssi = -100;
        try {
            WifiInfo wifiInfo = wm.getConnectionInfo();
            if (wifiInfo != null) {
                rssi = wifiInfo.getRssi();
                String rawSsid = wifiInfo.getSSID();
                if (rawSsid != null && !rawSsid.isEmpty() && !rawSsid.equals("<unknown ssid>")) {
                    ssid = rawSsid.replace("\"", "");
                }
            }
        } catch (Throwable ignored) {}

        String ip = getLocalIpAddress();

        String summary;
        if (isWifiConnected) {
            summary = (ssid != null && !ssid.isEmpty()) ? ssid : "Connected (" + ip + ")";
        } else {
            summary = "Not Connected";
        }

        return new WifiState(isWifiConnected, true, ssid, ip, rssi, summary);
    }

    public static BtState getBluetoothState(Context context) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return new BtState(false, false, 0, Collections.emptyList(), "Unavailable");
        }

        boolean isEnabled = adapter.isEnabled();
        if (!isEnabled) {
            return new BtState(false, true, 0, Collections.emptyList(), "Off");
        }

        int pairedCount = 0;
        List<String> connectedNames = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                pairedCount = bonded.size();
                for (BluetoothDevice dev : bonded) {
                    // Check if device is connected via standard profiles if possible
                    int a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
                    int head = adapter.getProfileConnectionState(BluetoothProfile.HEADSET);
                    if (a2dp == BluetoothProfile.STATE_CONNECTED || head == BluetoothProfile.STATE_CONNECTED) {
                        String name = dev.getName();
                        if (name != null && !name.isEmpty()) {
                            connectedNames.add(name);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        String summary;
        if (!connectedNames.isEmpty()) {
            summary = connectedNames.get(0) + (connectedNames.size() > 1 ? " (+" + (connectedNames.size() - 1) + ")" : "");
        } else if (pairedCount > 0) {
            summary = pairedCount + " Paired Device" + (pairedCount > 1 ? "s" : "");
        } else {
            summary = "On, Ready to Pair";
        }

        return new BtState(true, true, pairedCount, connectedNames, summary);
    }

    public static Intent getWifiSettingsIntent() {
        return new Intent(Settings.ACTION_WIFI_SETTINGS);
    }

    public static Intent getBluetoothSettingsIntent() {
        return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    public static Intent getSoundSettingsIntent() {
        return new Intent(Settings.ACTION_SOUND_SETTINGS);
    }

    public static Intent getDeviceSettingsIntent() {
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }
}
