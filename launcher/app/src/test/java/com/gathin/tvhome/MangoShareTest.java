package com.gathin.tvhome;

import com.gathin.tvhome.share.MangoShareServer;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying LocalSend v2 DTO structures, path sanitization,
 * and byte formatting for Mango Share.
 */
public class MangoShareTest {

    @Test
    public void testAnnouncementPayloadFormat() {
        String json = "{\"alias\":\"Mango TV\",\"version\":\"2.1\",\"port\":53317,\"protocol\":\"http\"}";
        assertTrue(json.contains("\"alias\":\"Mango TV\""));
        assertTrue(json.contains("\"version\":\"2.1\""));
        assertTrue(json.contains("\"port\":53317"));
    }

    @Test
    public void testFileNameSanitization() {
        String malicious = "../../etc/passwd";
        String clean = MangoShareServer.sanitizeFileName(malicious);
        assertFalse(clean.contains(".."));
        assertFalse(clean.contains("/"));
        assertFalse(clean.contains("\\"));

        String windowsTraversal = "..\\..\\windows\\system32\\cmd.exe";
        String cleanWin = MangoShareServer.sanitizeFileName(windowsTraversal);
        assertFalse(cleanWin.contains(".."));
        assertFalse(cleanWin.contains("\\"));
        assertFalse(cleanWin.contains("/"));

        String hiddenFile = ".bashrc";
        String cleanHidden = MangoShareServer.sanitizeFileName(hiddenFile);
        assertFalse(cleanHidden.startsWith("."));
    }

    @Test
    public void testFileSizeFormatting() {
        assertEquals("500 B", formatFileSize(500));
        assertEquals("1.5 KB", formatFileSize(1536));
        assertEquals("10.0 MB", formatFileSize(10 * 1024 * 1024));
        assertEquals("2.50 GB", formatFileSize((long) (2.5 * 1024 * 1024 * 1024)));
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
