package com.andrea.rxhome;

import java.net.ServerSocket;
import java.util.List;
import org.json.JSONObject;

public class LgTest {

    /** A fake webOS TV: registers when the client-key matches (or always when pairing with allow=true). */
    static WsTest.Reply tv(boolean allow) {
        return t -> {
            try {
                JSONObject m = new JSONObject(t);
                String id = m.optString("id");
                if (m.getString("type").equals("register")) {
                    return allow ? "{\"type\":\"registered\",\"id\":\"" + id + "\",\"payload\":{\"client-key\":\"CK\"}}"
                        : "{\"type\":\"error\",\"id\":\"" + id + "\",\"error\":\"403 User denied access\"}";
                }
                if (m.getString("uri").endsWith("getPowerState")) {
                    return "{\"type\":\"response\",\"id\":\"" + id + "\",\"payload\":{\"state\":\"Active\"}}";
                }
                return "{\"type\":\"response\",\"id\":\"" + id + "\",\"payload\":{\"returnValue\":true}}";
            } catch (Exception e) {
                return null;
            }
        };
    }

    public static void main(String[] a) throws Exception {
        assert Lg.buttonMessage(23).equals("type:button\nname:ENTER\n\n");
        assert Lg.buttonMessage(4).equals("type:button\nname:BACK\n\n");
        boolean unsupported = false;
        try {
            Lg.buttonMessage(999);
        } catch (UnsupportedOperationException e) {
            unsupported = true;
        }
        assert unsupported;

        JSONObject r = new JSONObject(Lg.request(7, "ssap://audio/setVolume", new JSONObject().put("volume", 12)));
        assert r.getString("type").equals("request") && r.getString("id").equals("7");
        assert r.getString("uri").equals("ssap://audio/setVolume") && r.getJSONObject("payload").getInt("volume") == 12;

        JSONObject reg = new JSONObject(Lg.registerMessage("KEY1"));
        assert reg.getString("type").equals("register");
        assert reg.getJSONObject("payload").getString("client-key").equals("KEY1");
        assert reg.getJSONObject("payload").getString("pairingType").equals("PROMPT");
        assert reg.getJSONObject("payload").getJSONObject("manifest").getJSONArray("signatures").length() == 1;
        assert !new JSONObject(Lg.registerMessage(null)).getJSONObject("payload").has("client-key");

        assert Lg.parsePower("{\"type\":\"response\",\"payload\":{\"state\":\"Active Standby\"}}") == Boolean.FALSE;
        assert Lg.parsePower("{\"type\":\"response\",\"payload\":{\"state\":\"Active\"}}") == Boolean.TRUE;
        assert Lg.parsePower("nonsense") == null;

        List<String[]> apps = Lg.parseApps("{\"type\":\"response\",\"payload\":{\"launchPoints\":["
            + "{\"id\":\"netflix\",\"title\":\"Netflix\"},{\"id\":\"youtube.leanback.v4\",\"title\":\"YouTube\"}]}}");
        assert apps.size() == 2 && apps.get(0)[0].equals("netflix") && apps.get(1)[1].equals("YouTube");

        Object[] tls = WsTest.tls();
        javax.net.ssl.SSLContext ctx = (javax.net.ssl.SSLContext) tls[0];
        String pin = (String) tls[1];

        try (ServerSocket ss = WsTest.server(ctx, null, false, tv(true))) {
            Lg.Pairing p = Lg.pair("127.0.0.1:" + ss.getLocalPort());
            assert p.clientKey.equals("CK") && p.pin.equals(pin);
        }

        // pairDenied
        try (ServerSocket ss = WsTest.server(ctx, null, false, tv(false))) {
            boolean denied = false;
            try {
                Lg.pair("127.0.0.1:" + ss.getLocalPort());
            } catch (java.io.IOException e) {
                denied = e.getMessage().contains("said no");
            }
            assert denied;
        }

        // Commands: register once with the saved key, then requests answered by id.
        WsTest.seen.clear();
        try (ServerSocket ss = WsTest.server(ctx, null, false, tv(true))) {
            Lg lg = new Lg("127.0.0.1:" + ss.getLocalPort(), "CK", pin, null);
            lg.setVolume(12);
            lg.mute();
            assert lg.isOn() == Boolean.TRUE;
            assert WsTest.seen.stream().filter(x -> x.startsWith("GET ")).count() == 1 : WsTest.seen;
            assert WsTest.seen.stream().anyMatch(x -> x.contains("\"client-key\":\"CK\"")) : WsTest.seen;
            assert WsTest.seen.stream().anyMatch(x -> x.contains("ssap://audio/setMute")) : WsTest.seen;
            assert lg.can("volume") && lg.can("apps");
        }
    }
}
