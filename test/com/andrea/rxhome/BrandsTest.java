package com.andrea.rxhome;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** WebSocket framing, LG webOS (SSAP) and Samsung Tizen messages, the TV list's brands. */
public class BrandsTest {
    public static void main(String[] a) throws Exception {
        // --- WebSocket (RFC 6455) ---
        assert Ws.accept("dGhlIHNhbXBsZSBub25jZQ==").equals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");      // the RFC's example
        byte[] mask = {1, 2, 3, 4};
        byte[] f = Ws.frame(1, "Hi".getBytes(StandardCharsets.UTF_8), mask);
        assert f[0] == (byte) 0x81 && f[1] == (byte) 0x82 && f.length == 2 + 4 + 2;               // FIN text, masked, len 2
        assert f[6] == ('H' ^ 1) && f[7] == ('i' ^ 2);
        assert Ws.frame(1, new byte[200], mask)[1] == (byte) (0x80 | 126);                          // 16-bit length
        assert Ws.frame(1, new byte[70000], mask)[1] == (byte) (0x80 | 127);                        // 64-bit length
        // A server frame (unmasked) back to its text, also when split in two.
        byte[] hello = {(byte) 0x81, 5, 'h', 'e', 'l', 'l', 'o'};
        assert new String(Ws.read(new ByteArrayInputStream(hello)).payload, StandardCharsets.UTF_8).equals("hello");
        byte[] split = {0x01, 3, 'h', 'e', 'l', (byte) 0x80, 2, 'l', 'o'};
        Ws.Frame whole = Ws.read(new ByteArrayInputStream(split));
        assert whole.opcode == 1 && new String(whole.payload, StandardCharsets.UTF_8).equals("hello");
        byte[] big = new byte[3 + 1 + 300];
        big[0] = (byte) 0x82;
        big[1] = 126;
        big[2] = 1;
        big[3] = 44; // 300
        assert Ws.read(new ByteArrayInputStream(big, 0, 4 + 300)).payload.length == 300;

        // --- LG webOS ---
        JSONObject reg = new JSONObject(Lg.registerMessage(null));
        assert reg.getString("type").equals("register") && reg.getJSONObject("payload").getString("pairingType").equals("PROMPT");
        assert reg.getJSONObject("payload").getJSONObject("manifest").getJSONObject("signed").getString("appId").equals("com.lge.test");
        assert !reg.getJSONObject("payload").has("client-key");
        assert new JSONObject(Lg.registerMessage("KEY1")).getJSONObject("payload").getString("client-key").equals("KEY1");
        JSONObject req = new JSONObject(Lg.request("7", "ssap://audio/setVolume", "{\"volume\":12}"));
        assert req.getString("type").equals("request") && req.getString("uri").equals("ssap://audio/setVolume")
            && req.getJSONObject("payload").getInt("volume") == 12;
        assert "abc".equals(Lg.clientKey("{\"type\":\"registered\",\"id\":\"reg0\",\"payload\":{\"client-key\":\"abc\"}}"));
        assert Lg.clientKey("{\"type\":\"response\",\"id\":\"reg0\",\"payload\":{\"pairingType\":\"PROMPT\"}}") == null;
        assert Lg.volume("{\"type\":\"response\",\"payload\":{\"volume\":23,\"muted\":false}}") == 23;
        assert Lg.volume("{\"type\":\"response\",\"payload\":{\"volumeStatus\":{\"volume\":9}}}") == 9;           // newer webOS
        assert Lg.muted("{\"type\":\"response\",\"payload\":{\"volume\":23,\"muted\":true}}");
        assert "a8:23:fe:01:02:03".equals(Lg.mac("{\"payload\":{\"device_id\":\"a8:23:fe:01:02:03\"}}"));

        // --- Samsung Tizen ---
        assert Samsung.url("192.168.1.30", "rxhome", null)
            .equals("wss://192.168.1.30:8002/api/v2/channels/samsung.remote.control?name=cnhob21l");
        assert Samsung.url("192.168.1.30", "rxhome", "123").endsWith("?name=cnhob21l&token=123");
        JSONObject k = new JSONObject(Samsung.keyMessage("KEY_VOLUP"));
        assert k.getString("method").equals("ms.remote.control")
            && k.getJSONObject("params").getString("DataOfCmd").equals("KEY_VOLUP")
            && k.getJSONObject("params").getString("Cmd").equals("Click");
        assert "9876".equals(Samsung.token("{\"event\":\"ms.channel.connect\",\"data\":{\"token\":\"9876\"}}"));
        assert "".equals(Samsung.token("{\"event\":\"ms.channel.connect\",\"data\":{}}"));       // older models: no token
        assert Samsung.token("{\"event\":\"ms.channel.unauthorized\"}") == null;
        String api = "{\"device\":{\"name\":\"[TV] Samsung Q60\",\"PowerState\":\"standby\",\"wifiMac\":\"64:1c:ae:00:11:22\",\"type\":\"Samsung SmartTV\"}}";
        assert Boolean.FALSE.equals(Samsung.powerOn(api)) && "64:1c:ae:00:11:22".equals(Samsung.mac(api));
        assert "[TV] Samsung Q60".equals(Samsung.name(api)) && Samsung.isSamsung(api) && !Samsung.isSamsung("{}");
        assert Samsung.powerOn("{\"device\":{\"type\":\"Samsung SmartTV\"}}") == null;              // older: not said
        assert Samsung.keyFor("VolumeUp").equals("KEY_VOLUP") && Samsung.keyFor("Mute").equals("KEY_MUTE");

        // --- The TV list knows each TV's brand (older lists: Philips) ---
        java.util.List<Tvs.Tv> tvs = Tvs.parse("tv1|Salotto|192.168.1.49|192.168.1.221\ntv2|Camera|192.168.1.50|192.168.1.30|samsung\ntv3|Studio||192.168.1.31|lg");
        assert tvs.get(0).kind.equals(Tvs.PHILIPS) && tvs.get(1).kind.equals(Tvs.SAMSUNG) && tvs.get(2).kind.equals(Tvs.LG);
        assert Tvs.format(tvs).equals("tv1|Salotto|192.168.1.49|192.168.1.221\ntv2|Camera|192.168.1.50|192.168.1.30|samsung\ntv3|Studio||192.168.1.31|lg");
        assert Tvs.describe(tvs.get(2), true, ip -> ip).equals("192.168.1.31 · LG (paired): power and volume only");
        assert Tvs.describe(tvs.get(1), false, ip -> ip).equals("192.168.1.50 · 192.168.1.30 · Samsung (not paired)");
        assert Arrays.asList(Tvs.KINDS).equals(Arrays.asList("philips", "samsung", "lg"));
    }
}
