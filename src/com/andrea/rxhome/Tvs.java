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
                if (s.id.isEmpty() && !s.pin.isEmpty() && (s.brand.equals("philips") || s.brand.equals("lg"))) s.id = "pin:" + s.pin;
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
     * from the old settings has no FireStick id yet: it adopts the stick at its old IP, else the only one here.
     */
    static List<Saved> match(List<Saved> saved, List<Discovery.TvFound> found) {
        List<Saved> out = new ArrayList<>();
        for (Saved s : saved) {
            if (s.hasStick() && s.stickId.isEmpty()) s.stickId = adopt(found, "firetv", s.stickIp);
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

    /** A FireStick on its own (brand none), as added from Settings. */
    static Saved stick(String ip, String serial) {
        Saved s = new Saved();
        s.name = "FireStick";
        s.stickIp = ip;
        s.stickId = serial;
        return s;
    }

    /**
     * Saves a newly paired TV or FireStick into list: the same one again is updated in place (keeping its
     * uid, FireStick and switch); a FireStick joins the one saved TV found here that has none; a TV takes
     * the one lone FireStick found here. scan = what this Wi-Fi shows now.
     */
    static void add(List<Saved> list, Saved n, List<Discovery.TvFound> scan) {
        if (n.brand.equals("none")) {
            for (Saved s : list) {
                if (s.hasStick() && s.stickId.equals(n.stickId)) {
                    s.stickIp = n.stickIp;
                    return;
                }
            }
            Saved only = null;
            int count = 0;
            for (Saved s : list) {
                if (s.brand.equals("none") || s.hasStick() || !here(scan, s.brand, s.id)) continue;
                only = s;
                count++;
            }
            if (count == 1) {
                only.stickIp = n.stickIp;
                only.stickId = n.stickId;
            } else {
                list.add(n);
            }
            return;
        }
        for (Saved s : list) {
            if (s.brand.equals(n.brand) && !n.id.isEmpty() && s.id.equals(n.id)) {
                s.ip = n.ip;
                s.name = n.name;
                s.mac = n.mac.isEmpty() ? s.mac : n.mac;
                s.auth = n.auth;
                s.pass = n.pass;
                s.pin = n.pin;
                return;
            }
        }
        Saved lone = null;
        int count = 0;
        for (Saved s : list) {
            if (!s.brand.equals("none") || !here(scan, "firetv", s.stickId)) continue;
            lone = s;
            count++;
        }
        if (count == 1) {
            n.stickIp = lone.stickIp;
            n.stickId = lone.stickId;
            n.uid = lone.uid; // the same entry, now with its TV
            list.set(list.indexOf(lone), n);
        } else {
            list.add(n);
        }
    }

    private static boolean here(List<Discovery.TvFound> scan, String brand, String id) {
        if (id.isEmpty()) return false;
        for (Discovery.TvFound f : scan) if (f.brand.equals(brand) && id.equals(f.id)) return true;
        return false;
    }

    /** Moves the TV at i one place (delta -1 up, +1 down); nothing past either end. */
    static void move(List<Saved> list, int i, int delta) {
        int j = i + delta;
        if (i < 0 || i >= list.size() || j < 0 || j >= list.size()) return;
        list.add(j, list.remove(i));
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
            s.id = cert == null || cert.isEmpty() ? "" : "pin:" + cert; // what Discovery reports for it
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
