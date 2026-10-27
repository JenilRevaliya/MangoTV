package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.gathin.tvhome.airplay.alac.AlacDecodeUtils;
import com.gathin.tvhome.airplay.alac.AlacFile;

public class AirPlayAudioTest {

    @Test
    public void testAlacContextCreation() {
        AlacFile alac = AlacDecodeUtils.create_alac(16, 2);
        assertNotNull(alac);
        assertEquals(16, alac.samplesize);
        assertEquals(2, alac.numchannels);
        assertEquals(4, alac.bytespersample);
        assertEquals(352, alac.setinfo_max_samples_per_frame);
        assertEquals(44100, alac.setinfo_8a_rate);
    }

    @Test
    public void testDnsSdTxtRecordEncoding() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("ch", "2");
        map.put("et", "0,1");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String kv = entry.getKey() + "=" + entry.getValue();
            byte[] b = kv.getBytes(StandardCharsets.UTF_8);
            baos.write(b.length);
            baos.write(b, 0, b.length);
        }
        byte[] encoded = baos.toByteArray();

        // Entry 1: length 4 ("ch=2")
        assertEquals(4, encoded[0]);
        assertEquals("ch=2", new String(encoded, 1, 4, StandardCharsets.UTF_8));

        // Entry 2: length 6 ("et=0,1")
        assertEquals(6, encoded[5]);
        assertEquals("et=0,1", new String(encoded, 6, 6, StandardCharsets.UTF_8));
    }

    @Test
    public void testFmtpParsingTokens() {
        String fmtp = "96 352 0 16 40 10 14 2 255 0 0 44100";
        String[] tokens = fmtp.trim().split("\\s+");
        assertEquals(12, tokens.length);
        assertEquals(352, Integer.parseInt(tokens[1]));
        assertEquals(16, Integer.parseInt(tokens[3]));
        assertEquals(44100, Integer.parseInt(tokens[11]));
    }
}
