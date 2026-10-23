package com.gathin.tvhome.bluetooth;

public class AirPodsDevice {
    public final String address;
    public String modelName;
    public int modelId;
    public int leftBattery = -1;
    public int rightBattery = -1;
    public int caseBattery = -1;
    public boolean isLeftCharging;
    public boolean isRightCharging;
    public boolean isCaseCharging;
    public boolean isLeftInEar;
    public boolean isRightInEar;
    public boolean lidOpen;
    public String colorName = "White";
    public int rssi;
    public long lastSeenMs;

    public AirPodsDevice(String address) {
        this.address = address;
        this.lastSeenMs = System.currentTimeMillis();
    }

    public static String resolveModelName(int modelId) {
        switch (modelId) {
            case 0x0E20: return "AirPods Pro";
            case 0x1420: return "AirPods Pro 2";
            case 0x2420: return "AirPods Pro 2 (USB-C)";
            case 0x0220: return "AirPods (1st Gen)";
            case 0x0F20: return "AirPods (2nd Gen)";
            case 0x1320: return "AirPods (3rd Gen)";
            case 0x1920: return "AirPods 4";
            case 0x1B20: return "AirPods 4 (ANC)";
            case 0x0A20: return "AirPods Max";
            case 0x1F20: return "AirPods Max (USB-C)";
            case 0x0520: return "Beats Solo Pro";
            case 0x1020: return "Beats Studio Buds";
            case 0x1120: return "Beats Fit Pro";
            case 0x1720: return "Beats Studio Pro";
            default: return "AirPods";
        }
    }

    public static String resolveColor(int colorId) {
        switch (colorId) {
            case 0x00: return "White";
            case 0x01: return "Black";
            case 0x02: return "Red";
            case 0x03: return "Blue";
            case 0x04: return "Pink";
            case 0x05: return "Gray";
            case 0x06: return "Silver";
            case 0x07: return "Gold";
            case 0x08: return "Rose Gold";
            case 0x09: return "Space Gray";
            case 0x0A: return "Dark Blue";
            case 0x0B: return "Light Blue";
            case 0x0C: return "Yellow";
            default: return "White";
        }
    }

    public String formatBattery(int level) {
        return level >= 0 ? (level + "%") : "--%";
    }
}
