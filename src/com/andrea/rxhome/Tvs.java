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
        // uid: rxhome's own key for this entry (which TV is active, the remote switch), never changes.
        String uid = java.util.UUID.randomUUID().toString();
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
                if (!o.optString("uid").isEmpty()) s.uid = o.optString("uid");
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
                o.put("uid", s.uid);
                a.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return a.toString();
    }

    /**
     * The saved TVs on this Wi-Fi now: the TV or its FireStick found by id (IPs updated in place). An entry
     * from the old settings has no ids yet: it adopts what answers at its old IP, else the only one of its kind here.
     */
    static List<Saved> match(List<Saved> saved, List<Discovery.TvFound> found) {
        List<Saved> out = new ArrayList<>();
        for (Saved s : saved) {
            if (s.hasStick() && s.stickId.isEmpty()) s.stickId = adopt(found, "firetv", s.stickIp);
            if (!s.brand.equals("none") && s.id.isEmpty()) s.id = adopt(found, s.brand, s.ip);
            boolean here = false;
            for (Discovery.TvFound f : found) {
                if (f.id == null) continue;
                if (f.brand.equals("firetv") && s.hasStick() && f.id.equals(s.stickId)) {
                    s.stickIp = f.ip;
                    here = true;
                } else if (f.brand.equals(s.brand) && f.id.equals(s.id)) {
                    s.ip = f.ip;
                    here = true;
                }
            }
            if (here) out.add(s);
        }
        return out;
    }

    /** For an entry without an id yet: what answers at its old IP, else the only one of that brand here; "" if unsure. */
    private static String adopt(List<Discovery.TvFound> found, String brand, String ip) {
        String only = null;
        int n = 0;
        for (Discovery.TvFound f : found) {
            if (!f.brand.equals(brand) || f.id == null) continue;
            if (f.ip.equals(ip)) return f.id;
            only = f.id;
            n++;
        }
        return n == 1 ? only : "";
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
