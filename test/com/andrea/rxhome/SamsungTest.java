package com.andrea.rxhome;

import java.net.ServerSocket;
import java.util.List;
import org.json.JSONObject;

public class SamsungTest {

    static final String CONNECTED = "{\"data\":{\"clients\":[],\"id\":\"x\",\"token\":\"12345678\"},\"event\":\"ms.channel.connect\"}";

    public static void main(String[] a) throws Exception {
        JSONObject k = new JSONObject(Samsung.keyMessage(19));
        assert k.getString("method").equals("ms.remote.control");
        assert k.getJSONObject("params").getString("DataOfCmd").equals("KEY_UP");
        assert k.getJSONObject("params").getString("Cmd").equals("Click");
        assert new JSONObject(Samsung.keyMessage(4)).getJSONObject("params").getString("DataOfCmd").equals("KEY_RETURN");
        assert new JSONObject(Samsung.keyMessage(178)).getJSONObject("params").getString("DataOfCmd").equals("KEY_SOURCE");
        boolean unsupported = false;
        try {
            Samsung.keyMessage(999);
        } catch (UnsupportedOperationException e) {
            unsupported = true;
        }
        assert unsupported;

        assert Samsung.channelUrl("1.2.3.4", "T").equals(
            "wss://1.2.3.4:8002/api/v2/channels/samsung.remote.control?name=cnhob21l&token=T") : Samsung.channelUrl("1.2.3.4", "T");
        assert Samsung.channelUrl("1.2.3.4:99", null).endsWith(":99/api/v2/channels/samsung.remote.control?name=cnhob21l");

        assert Samsung.parsePower("{\"device\":{\"PowerState\":\"standby\"}}") == Boolean.FALSE;
        assert Samsung.parsePower("{\"device\":{\"PowerState\":\"on\"}}") == Boolean.TRUE;
        assert Samsung.parsePower("{\"device\":{}}") == Boolean.TRUE; // older models: answering = on
        assert Samsung.parsePower("") == null;
        // Another Samsung at the saved IP (another home): its power isn't ours.
        assert Samsung.power("{\"device\":{\"id\":\"uuid:B\",\"PowerState\":\"on\"}}", "uuid:A") == null;
        assert Samsung.power("{\"device\":{\"id\":\"uuid:A\",\"PowerState\":\"on\"}}", "uuid:A") == Boolean.TRUE;

        String[] info = Samsung.parseInfo("{\"device\":{\"id\":\"uuid:abc\",\"name\":\"[TV] Samsung Q60\",\"wifiMac\":\"aa:bb:cc:dd:ee:ff\"}}");
        assert info[0].equals("uuid:abc") && info[1].equals("[TV] Samsung Q60") && info[2].equals("aa:bb:cc:dd:ee:ff");
        assert Samsung.parseInfo("{\"other\":1}") == null && Samsung.parseInfo("") == null;

        List<String[]> apps = Samsung.parseApps("{\"data\":{\"data\":[{\"appId\":\"111299001912\",\"app_type\":2,\"name\":\"YouTube\"},"
            + "{\"appId\":\"3201907018807\",\"name\":\"Netflix\"}]},\"event\":\"ed.installedApp.get\",\"from\":\"host\"}");
        assert apps.size() == 2 && apps.get(0)[0].equals("111299001912") && apps.get(1)[1].equals("Netflix");

        Object[] tls = WsTest.tls();
        javax.net.ssl.SSLContext ctx = (javax.net.ssl.SSLContext) tls[0];
        String pin = (String) tls[1];

        // Pairing: the TV answers with a token after the user chooses Allow.
        try (ServerSocket ss = WsTest.server(ctx, CONNECTED, false, t -> null)) {
            Samsung.Pairing p = Samsung.pair("127.0.0.1:" + ss.getLocalPort());
            assert p.token.equals("12345678") && p.pin.equals(pin);
        }

        // pairDenied: the user chose Deny.
        try (ServerSocket ss = WsTest.server(ctx, "{\"event\":\"ms.channel.unauthorized\"}", false, t -> null)) {
            boolean denied = false;
            try {
                Samsung.pair("127.0.0.1:" + ss.getLocalPort());
            } catch (java.io.IOException e) {
                denied = e.getMessage().contains("said no");
            }
            assert denied;
        }

        // Keys reuse one connection, carrying the token.
        WsTest.seen.clear();
        try (ServerSocket ss = WsTest.server(ctx, CONNECTED, false, t -> null)) {
            Samsung s = new Samsung("127.0.0.1:" + ss.getLocalPort(), "tok", pin, null);
            s.key(19);
            s.key(23);
            Thread.sleep(200);
            long gets = WsTest.seen.stream().filter(x -> x.startsWith("GET ")).count();
            assert gets == 1 : WsTest.seen;
            assert WsTest.seen.get(0).endsWith("token=tok") : WsTest.seen;
            assert WsTest.seen.stream().anyMatch(x -> x.contains("KEY_ENTER")) : WsTest.seen;
            assert !s.can("volume") && s.can("mute") && s.can("apps");
        }

        // Wake-on-LAN moved to Screen.
        byte[] wol = Screen.magicPacket("aa:BB-cc:dd:ee:FF");
        assert wol.length == 102 && wol[0] == (byte) 0xff && wol[6] == (byte) 0xaa && wol[101] == (byte) 0xff;
    }
}
