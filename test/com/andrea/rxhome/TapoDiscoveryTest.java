package com.andrea.rxhome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TapoDiscoveryTest {
    public static void main(String[] a) throws Exception {
        // A reply as the L900-5 strips send it: 16-byte header, then JSON.
        String json = "{\"result\":{\"device_type\":\"SMART.TAPOBULB\",\"device_model\":\"L900-5(EU)\","
            + "\"ip\":\"192.168.1.10\",\"mac\":\"AA-BB-CC-DD-EE-FF\",\"mgt_encrypt_schm\":{\"encrypt_type\":\"TPAP\"}},\"error_code\":0}";
        byte[] reply = new byte[16 + json.length()];
        System.arraycopy(json.getBytes("UTF-8"), 0, reply, 16, json.length());
        TapoDiscovery.Found f = TapoDiscovery.parse(reply, "192.168.1.10");
        assert f.ip.equals("192.168.1.10") && f.model.equals("L900") : f.model;
        assert f.fallbackName().equals("L900 .10") : f.fallbackName();
        assert TapoDiscovery.parse(new byte[20], "1.2.3.4") == null; // junk
        // Not a light or plug (e.g. a camera): ignored.
        String cam = json.replace("SMART.TAPOBULB", "SMART.IPCAMERA");
        byte[] r2 = new byte[16 + cam.length()];
        System.arraycopy(cam.getBytes("UTF-8"), 0, r2, 16, cam.length());
        assert TapoDiscovery.parse(r2, "192.168.1.10") == null;

        // Merging what was found into the lights setting.
        Map<String, String> found = new LinkedHashMap<>();
        found.put("192.168.1.10", "Desk strip");   // logged in: real nickname
        found.put("192.168.1.13", "L900 .13");     // couldn't log in yet: placeholder
        // New lights are added.
        assert TapoDiscovery.merge("", found).equals("Desk strip=192.168.1.10\nL900 .13=192.168.1.13")
            : TapoDiscovery.merge("", found);
        // A light that moved keeps its name; a placeholder is replaced once the real name is known;
        // names the user typed are kept.
        String before = "Desk strip=192.168.1.99\nL900 .10=192.168.1.10\nMy name=192.168.1.13";
        found.clear();
        found.put("192.168.1.10", "Desk strip");
        found.put("192.168.1.13", "Bed strip");
        assert TapoDiscovery.merge(before, found).equals("Desk strip=192.168.1.10\nMy name=192.168.1.13")
            : TapoDiscovery.merge(before, found);

        // Live (RXHOME_TAPO_DISCOVER set): the real strips answer the broadcast.
        if (System.getenv("RXHOME_TAPO_DISCOVER") != null) {
            List<TapoDiscovery.Found> live = TapoDiscovery.discover(1500);
            System.out.println("  live: " + live.size() + " Tapo device(s)");
            assert !live.isEmpty();
        }
    }
}
