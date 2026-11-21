package com.andrea.rxhome;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every TV rxhome has paired, in any home, kept as one JSON list. A TV is found again by its id (never by
 * IP: many homes use the same addresses), and its IP is updated if it moved.
 */
final class Tvs {

    /** brand: samsung | lg | philips | none (a FireStick alone). auth: Samsung token / LG client-key / Philips user. */
    static final class Saved {
        String id = "", name = "", brand = "none", ip = "", mac = "", auth = "", pass = "", pin = "", stickIp = "", stickId = "";
        boolean inputToTv;

        boolean hasStick() {
            return !stickIp.isEmpty();
        }
    }

    private static final String[] FIELDS = {"id", "name", "brand", "ip", "mac", "auth", "pass", "pin", "stickIp", "stickId"};

    private Tvs() {}

    static List<Saved> parse(String json) {
        List<Saved> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Saved s = new Saved();
                String[] v = new String[FIELDS.length];
                for (int f = 0; f < FIELDS.length; f++) v[f] = o.optString(FIELDS[f], "");
                set(s, v);
                s.inputToTv = o.optBoolean("inputToTv", false);
                out.add(s);
            }
        } catch (JSONException e) {
            // Empty or damaged: no TVs (they can be added again).
        }
        return out;
    }

    static String format(List<Saved> tvs) {
        JSONArray a = new JSONArray();
        try {
            for (Saved s : tvs) {
                JSONObject o = new JSONObject();
                String[] v = get(s);
                for (int f = 0; f < FIELDS.length; f++) o.put(FIELDS[f], v[f]);
                o.put("inputToTv", s.inputToTv);
                a.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return a.toString();
    }

    /**
     * The saved TVs on this Wi-Fi now: the TV or its FireStick found by id (IPs updated in place). An entry
     * from the old settings has no ids yet: it adopts those of what answers at its old IPs, once.
     */
    static List<Saved> match(List<Saved> saved, List<Discovery.TvFound> found) {
        List<Saved> out = new ArrayList<>();
        for (Saved s : saved) {
            boolean here = false;
            for (Discovery.TvFound f : found) {
                if (f.id == null) continue;
                if (f.brand.equals("firetv")) {
                    if (!s.hasStick()) continue;
                    if (s.stickId.isEmpty() && f.ip.equals(s.stickIp)) s.stickId = f.id;
                    if (f.id.equals(s.stickId)) {
                        s.stickIp = f.ip;
                        here = true;
                    }
                } else if (f.brand.equals(s.brand)) {
                    if (s.id.isEmpty() && f.ip.equals(s.ip)) s.id = f.id;
                    if (f.id.equals(s.id)) {
                        s.ip = f.ip;
                        here = true;
                    }
                }
            }
            if (here) out.add(s);
        }
        return out;
    }

    /** The settings before multi-TV: the FireStick, plus the Philips if it was paired. */
    static Saved migrate(String fireIp, String philipsIp, String user, String pass, String cert, String mac) {
        Saved s = new Saved();
        s.stickIp = fireIp == null ? "" : fireIp;
        if (user != null && !user.isEmpty()) {
            s.brand = "philips";
            s.name = "Philips TV";
            s.ip = philipsIp;
            s.auth = user;
            s.pass = pass;
            s.pin = cert;
            s.mac = mac == null ? "" : mac;
        } else {
            s.name = "FireStick";
        }
        return s;
    }

    private static String[] get(Saved s) {
        return new String[] {s.id, s.name, s.brand, s.ip, s.mac, s.auth, s.pass, s.pin, s.stickIp, s.stickId};
    }

    private static void set(Saved s, String[] v) {
        s.id = v[0];
        s.name = v[1];
        s.brand = v[2].isEmpty() ? "none" : v[2];
        s.ip = v[3];
        s.mac = v[4];
        s.auth = v[5];
        s.pass = v[6];
        s.pin = v[7];
        s.stickIp = v[8];
        s.stickId = v[9];
    }
}
