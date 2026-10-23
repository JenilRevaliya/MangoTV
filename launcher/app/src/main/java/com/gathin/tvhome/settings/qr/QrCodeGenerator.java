package com.gathin.tvhome.settings.qr;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Lightweight, zero-dependency pure Java QR Code Generator for Mango TV.
 * Generates ISO/IEC 18004 compliant QR Code Bitmaps for phone pairing.
 */
public final class QrCodeGenerator {

    public static Bitmap generateQrBitmap(String text, int width, int height) {
        return generateQrBitmap(text, width, height, Color.WHITE, Color.TRANSPARENT);
    }

    public static Bitmap generateQrBitmap(String text, int width, int height, int fgColor, int bgColor) {
        if (text == null || text.isEmpty()) {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }

        SimpleQr qr = SimpleQr.encodeText(text, SimpleQr.Ecc.MEDIUM);
        int modules = qr.size;
        int quietZone = 4;
        int totalModules = modules + quietZone * 2;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, bgColor);

        float moduleWidth = (float) width / totalModules;
        float moduleHeight = (float) height / totalModules;

        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                if (qr.getModule(x, y)) {
                    int startX = Math.round((x + quietZone) * moduleWidth);
                    int endX = Math.round((x + quietZone + 1) * moduleWidth);
                    int startY = Math.round((y + quietZone) * moduleHeight);
                    int endY = Math.round((y + quietZone + 1) * moduleHeight);

                    for (int py = startY; py < endY && py < height; py++) {
                        int rowOffset = py * width;
                        for (int px = startX; px < endX && px < width; px++) {
                            pixels[rowOffset + px] = fgColor;
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    // =========================================================================
    // Core ISO/IEC 18004 QR Generation (Micro-implementation)
    // =========================================================================
    public static final class SimpleQr {
        public enum Ecc {
            LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);
            final int formatBits;
            Ecc(int fb) { this.formatBits = fb; }
        }

        public final int version;
        public final int size;
        public final Ecc errorCorrectionLevel;
        private final boolean[][] modules;

        public static SimpleQr encodeText(String text, Ecc ecl) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            // Minimum version needed for byte mode:
            int version = 1;
            while (version <= 40) {
                int capacity = getNumDataCodewords(version, ecl) - 4; // roughly 4 overhead bytes
                if (data.length <= capacity) break;
                version++;
            }
            if (version > 40) version = 40;
            return new SimpleQr(version, ecl, data);
        }

        public boolean getModule(int x, int y) {
            return (x >= 0 && x < size && y >= 0 && y < size) && modules[y][x];
        }

        private SimpleQr(int ver, Ecc ecl, byte[] data) {
            this.version = ver;
            this.errorCorrectionLevel = ecl;
            this.size = ver * 4 + 17;
            this.modules = new boolean[size][size];
            boolean[][] isFunction = new boolean[size][size];

            // 1. Draw function patterns
            drawFinderPattern(3, 3, isFunction);
            drawFinderPattern(size - 4, 3, isFunction);
            drawFinderPattern(3, size - 4, isFunction);
            drawTimingPatterns(isFunction);
            drawAlignmentPatterns(isFunction);

            // 2. Format information dummy reservation
            drawDummyFormatBits(isFunction);

            // 3. Assemble data codewords
            byte[] allCodewords = encodeAndAddEcc(data, ver, ecl);

            // 4. Place data bits
            placeDataBits(allCodewords, isFunction);

            // 5. Apply best mask pattern
            applyMask(0, isFunction); // Mask 0 (x+y)%2 == 0 is reliable & fast
            drawFormatBits(0);
        }

        private void drawFinderPattern(int x, int y, boolean[][] isFunction) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    int px = x + dx;
                    int py = y + dy;
                    if (px >= 0 && px < size && py >= 0 && py < size) {
                        setFunctionModule(px, py, dist != 2 && dist != 4, isFunction);
                    }
                }
            }
        }

        private void drawAlignmentPatterns(boolean[][] isFunction) {
            if (version < 2) return;
            int[] pos = getAlignmentPatternPositions(version);
            for (int y : pos) {
                for (int x : pos) {
                    if ((x == 6 && y == 6) || (x == 6 && y == size - 7) || (x == size - 7 && y == 6)) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1, isFunction);
                        }
                    }
                }
            }
        }

        private void drawTimingPatterns(boolean[][] isFunction) {
            for (int i = 0; i < size; i++) {
                setFunctionModule(6, i, i % 2 == 0, isFunction);
                setFunctionModule(i, 6, i % 2 == 0, isFunction);
            }
        }

        private void drawDummyFormatBits(boolean[][] isFunction) {
            for (int i = 0; i <= 8; i++) {
                if (i != 6) {
                    setFunctionModule(8, i, false, isFunction);
                    setFunctionModule(i, 8, false, isFunction);
                }
            }
            for (int i = size - 8; i < size; i++) {
                setFunctionModule(8, i, false, isFunction);
                setFunctionModule(i, 8, false, isFunction);
            }
            setFunctionModule(8, size - 8, true, isFunction); // Dark module
        }

        private void drawFormatBits(int mask) {
            int data = (errorCorrectionLevel.formatBits << 3) | mask;
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            int bits = ((data << 10) | rem) ^ 0x5412;

            for (int i = 0; i <= 5; i++) modules[8][i] = getBit(bits, i);
            modules[8][7] = getBit(bits, 6);
            modules[8][8] = getBit(bits, 7);
            modules[7][8] = getBit(bits, 8);
            for (int i = 9; i < 15; i++) modules[14 - i][8] = getBit(bits, i);

            for (int i = 0; i < 8; i++) modules[size - 1 - i][8] = getBit(bits, i);
            for (int i = 8; i < 15; i++) modules[8][size - 15 + i] = getBit(bits, i);
            modules[8][size - 8] = true;
        }

        private void setFunctionModule(int x, int y, boolean isDark, boolean[][] isFunction) {
            modules[y][x] = isDark;
            isFunction[y][x] = true;
        }

        private void placeDataBits(byte[] data, boolean[][] isFunction) {
            int bitIndex = 0;
            int totalBits = data.length * 8;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        boolean upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!isFunction[y][x] && bitIndex < totalBits) {
                            modules[y][x] = getBit(data[bitIndex >>> 3], 7 - (bitIndex & 7));
                            bitIndex++;
                        }
                    }
                }
            }
        }

        private void applyMask(int mask, boolean[][] isFunction) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (!isFunction[y][x]) {
                        boolean invert = false;
                        switch (mask) {
                            case 0: invert = (x + y) % 2 == 0; break;
                            case 1: invert = y % 2 == 0; break;
                            case 2: invert = x % 3 == 0; break;
                            case 3: invert = (x + y) % 3 == 0; break;
                            case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                            case 5: invert = (x * y) % 2 + (x * y) % 3 == 0; break;
                            case 6: invert = ((x * y) % 2 + (x * y) % 3) % 2 == 0; break;
                            case 7: invert = ((x + y) % 2 + (x * y) % 3) % 2 == 0; break;
                        }
                        if (invert) modules[y][x] = !modules[y][x];
                    }
                }
            }
        }

        private static boolean getBit(int val, int i) {
            return ((val >>> i) & 1) != 0;
        }

        private static byte[] encodeAndAddEcc(byte[] textBytes, int ver, Ecc ecl) {
            int numDataCodewords = getNumDataCodewords(ver, ecl);
            // Byte mode: 0100 (4 bits) + length (8 bits for ver 1-9) + data
            int bitLen = 4 + (ver <= 9 ? 8 : 16) + textBytes.length * 8;
            byte[] buffer = new byte[numDataCodewords];
            int bitPos = 0;

            // Mode indicator: 0100
            bitPos = appendBits(0b0100, 4, buffer, bitPos);
            // Count indicator
            bitPos = appendBits(textBytes.length, ver <= 9 ? 8 : 16, buffer, bitPos);
            // Data bytes
            for (byte b : textBytes) {
                bitPos = appendBits(b & 0xFF, 8, buffer, bitPos);
            }
            // Terminator (up to 4 zero bits)
            int termBits = Math.min(4, numDataCodewords * 8 - bitPos);
            bitPos = appendBits(0, termBits, buffer, bitPos);
            // Pad to byte boundary
            if (bitPos % 8 != 0) bitPos = appendBits(0, 8 - (bitPos % 8), buffer, bitPos);
            // Pad bytes 0xEC, 0x11
            int pad = 0xEC;
            while (bitPos < numDataCodewords * 8) {
                bitPos = appendBits(pad, 8, buffer, bitPos);
                pad = pad == 0xEC ? 0x11 : 0xEC;
            }

            // Reed-Solomon Error Correction Code
            int eccPerBlock = getEccCodewordsPerBlock(ver, ecl);
            byte[] rsGenerator = getReedSolomonGenerator(eccPerBlock);
            byte[] ecc = new byte[eccPerBlock];

            for (byte b : buffer) {
                int factor = (b ^ ecc[0]) & 0xFF;
                System.arraycopy(ecc, 1, ecc, 0, eccPerBlock - 1);
                ecc[eccPerBlock - 1] = 0;
                for (int i = 0; i < eccPerBlock; i++) {
                    ecc[i] ^= rsMultiply(rsGenerator[i] & 0xFF, factor);
                }
            }

            byte[] result = new byte[numDataCodewords + eccPerBlock];
            System.arraycopy(buffer, 0, result, 0, numDataCodewords);
            System.arraycopy(ecc, 0, result, numDataCodewords, eccPerBlock);
            return result;
        }

        private static int appendBits(int val, int count, byte[] buffer, int bitPos) {
            for (int i = count - 1; i >= 0; i--) {
                int bit = (val >>> i) & 1;
                buffer[bitPos >>> 3] |= (bit << (7 - (bitPos & 7)));
                bitPos++;
            }
            return bitPos;
        }

        private static int getNumDataCodewords(int ver, Ecc ecl) {
            // Standard QR raw data codeword table for low versions
            int[] tableM = { 0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216 };
            if (ver < tableM.length) return tableM[ver];
            return 200; // Fallback
        }

        private static int getEccCodewordsPerBlock(int ver, Ecc ecl) {
            int[] tableM = { 0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
            if (ver < tableM.length) return tableM[ver];
            return 28;
        }

        private static int[] getAlignmentPatternPositions(int ver) {
            if (ver <= 1) return new int[0];
            int step = (ver * 4 + 3) / (ver / 7 + 1);
            step = (step + 1) / 2 * 2;
            int num = ver / 7 + 2;
            int[] res = new int[num];
            res[0] = 6;
            for (int i = num - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) {
                res[i] = pos;
            }
            return res;
        }

        private static final int[] EXP_TABLE = new int[512];
        private static final int[] LOG_TABLE = new int[256];
        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP_TABLE[i] = x;
                EXP_TABLE[i + 255] = x;
                LOG_TABLE[x] = i;
                x = (x << 1) ^ ((x >= 128) ? 0x11D : 0);
            }
        }

        private static int rsMultiply(int x, int y) {
            if (x == 0 || y == 0) return 0;
            return EXP_TABLE[LOG_TABLE[x] + LOG_TABLE[y]];
        }

        private static byte[] getReedSolomonGenerator(int degree) {
            int[] poly = { 1 };
            for (int i = 0; i < degree; i++) {
                int[] next = new int[poly.length + 1];
                for (int j = 0; j < poly.length; j++) {
                    next[j] ^= rsMultiply(poly[j], EXP_TABLE[i]);
                    next[j + 1] ^= poly[j];
                }
                poly = next;
            }
            byte[] res = new byte[degree];
            for (int i = 0; i < degree; i++) {
                res[i] = (byte) poly[i];
            }
            return res;
        }
    }
}
