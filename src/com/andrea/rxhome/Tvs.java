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

    /**
     * A TV and what's plugged into it (bound in its TV info page), or an add-on on its own (brand none:
     * one FireStick or one Sky box). brand: samsung | samsung-legacy | lg | philips | none.
     * auth: Samsung token / LG client-key / Philips user. input: the remote's style ("" = default).
     */
    static final class Saved {
        // uid: rxhome's own key for this entry (which TV is active, the remote style), never changes.
        String uid = java.util.UUID.randomUUID().toString();
        String id = "", name = "", brand = "none", ip = "", mac = "", auth = "", pass = "", pin = "", stickIp = "", stickId = "",
            skyIp = "", skyId = "", input = "";

        boolean hasStick() {
            return !stickIp.isEmpty();
        }

        boolean hasSky() {
            return !skyIp.isEmpty();
        }
    }

    private static final String[] FIELDS = {"id", "name", "brand", "ip", "mac", "auth", "pass", "pin", "stickIp", "stickId",
        "skyIp", "skyId", "input"};

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
                if (s.input.isEmpty() && o.optBoolean("inputToTv", false)) s.input = "tv"; // an earlier build's switch
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
                } else if (f.brand.equals("sky") && s.hasSky() && f.id.equals(s.skyId)) {
                    s.skyIp = f.ip;
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

    /** A Sky box on its own, as added from Settings. */
    static Saved sky(String ip, String id) {
        Saved s = new Saved();
        s.name = "Sky";
        s.skyIp = ip;
        s.skyId = id;
        return s;
    }

    /**
     * Saves a newly paired TV, FireStick or Sky box: the same one again (wherever it is, on a TV or alone)
     * is updated in place, keeping its uid, add-ons and remote style; anything new is added on its own.
     * Nothing is ever attached by itself (two FireSticks in one home): that's done in TV info.
     * True if it's new (false: already saved, just updated).
     */
    static boolean add(List<Saved> list, Saved n) {
        for (Saved s : list) {
            if (n.hasStick() && n.brand.equals("none") && s.hasStick() && s.stickId.equals(n.stickId)) {
                s.stickIp = n.stickIp;
                return false;
            }
            if (n.hasSky() && n.brand.equals("none") && s.hasSky() && s.skyId.equals(n.skyId)) {
                s.skyIp = n.skyIp;
                return false;
            }
            if (!n.brand.equals("none") && s.brand.equals(n.brand) && !n.id.isEmpty() && s.id.equals(n.id)) {
                s.ip = n.ip;
                s.name = n.name;
                s.mac = n.mac.isEmpty() ? s.mac : n.mac;
                s.auth = n.auth;
                s.pass = n.pass;
                s.pin = n.pin;
                return false;
            }
        }
        list.add(n);
        return true;
    }

    /** The FireSticks ("stick") or Sky boxes ("sky") saved on their own, free to bind to a TV. */
    static List<Saved> addOns(List<Saved> list, String kind) {
        List<Saved> out = new ArrayList<>();
        for (Saved s : list) {
            if (s.brand.equals("none") && (kind.equals("stick") ? s.hasStick() : s.hasSky())) out.add(s);
        }
        return out;
    }

    /** Puts the add-on saved on its own (addOnUid) onto the TV; one the TV had of that kind goes back on its own. */
    static void bind(List<Saved> list, String tvUid, String addOnUid) {
        Saved tv = find(list, tvUid), a = find(list, addOnUid);
        if (tv == null || a == null || tv == a || !a.brand.equals("none")) return;
        int at = list.indexOf(a);
        list.remove(a);
        if (a.hasStick()) {
            if (tv.hasStick()) list.add(at, stick(tv.stickIp, tv.stickId));
            tv.stickIp = a.stickIp;
            tv.stickId = a.stickId;
        } else if (a.hasSky()) {
            if (tv.hasSky()) list.add(at, sky(tv.skyIp, tv.skyId));
            tv.skyIp = a.skyIp;
            tv.skyId = a.skyId;
        }
    }

    /** Takes the TV's FireStick ("stick") or Sky box ("sky") off it, saving it on its own right after the TV. */
    static void unbind(List<Saved> list, String tvUid, String kind) {
        Saved tv = find(list, tvUid);
        if (tv == null) return;
        int at = list.indexOf(tv) + 1;
        if (kind.equals("stick") && tv.hasStick()) {
            list.add(at, stick(tv.stickIp, tv.stickId));
            tv.stickIp = tv.stickId = "";
        } else if (kind.equals("sky") && tv.hasSky()) {
            list.add(at, sky(tv.skyIp, tv.skyId));
            tv.skyIp = tv.skyId = "";
        }
    }

    /** Removes a TV; what was plugged into it stays, on its own. */
    static void remove(List<Saved> list, String uid) {
        Saved tv = find(list, uid);
        if (tv == null) return;
        if (!tv.brand.equals("none")) {
            unbind(list, uid, "sky");
            unbind(list, uid, "stick");
        }
        list.remove(tv);
    }

    static Saved find(List<Saved> list, String uid) {
        for (Saved s : list) if (s.uid.equals(uid)) return s;
        return null;
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
        return new String[] {s.id, s.name, s.brand, s.ip, s.mac, s.auth, s.pass, s.pin, s.stickIp, s.stickId, s.skyIp, s.skyId, s.input};
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
        s.skyIp = v[10];
        s.skyId = v[11];
        s.input = v[12];
    }
}
