package com.andrea.rxhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TvsTest {

    static Tvs.Saved saved(String brand, String id, String ip) {
        Tvs.Saved s = new Tvs.Saved();
        s.brand = brand;
        s.id = id;
        s.ip = ip;
        s.name = brand + " TV";
        return s;
    }

    static Discovery.TvFound found(String brand, String ip, String id) {
        return new Discovery.TvFound(brand, ip, id, brand, null);
    }

    public static void main(String[] a) throws Exception {
        // Round trip.
        Tvs.Saved one = saved("samsung", "uuid:A", "192.168.1.221");
        one.auth = "tok";
        one.pin = "ab12";
        one.mac = "aa:bb:cc:dd:ee:ff";
        Tvs.Saved two = saved("philips", "", "10.0.0.5");
        two.auth = "user";
        two.pass = "pass";
        two.stickIp = "10.0.0.6";
        two.stickId = "G070";
        two.input = "tv";
        two.skyIp = "10.0.0.7";
        two.skyId = "uuid:SKY";
        List<Tvs.Saved> back = Tvs.parse(Tvs.format(Arrays.asList(one, two)));
        assert back.size() == 2;
        assert back.get(0).auth.equals("tok") && back.get(0).pin.equals("ab12") && back.get(0).mac.equals("aa:bb:cc:dd:ee:ff");
        assert back.get(1).pass.equals("pass") && back.get(1).stickId.equals("G070") && back.get(1).input.equals("tv");
        assert back.get(1).skyIp.equals("10.0.0.7") && back.get(1).skyId.equals("uuid:SKY") && back.get(1).hasSky();
        assert back.get(0).input.isEmpty() && !back.get(0).hasSky();
        // Saved by an earlier build: the remote switch on TV.
        assert Tvs.parse("[{\"brand\":\"lg\",\"inputToTv\":true}]").get(0).input.equals("tv");
        // Each saved TV has its own lasting local key, kept across saves.
        assert !back.get(0).uid.isEmpty() && !back.get(0).uid.equals(back.get(1).uid);
        assert Tvs.parse(Tvs.format(back)).get(1).uid.equals(back.get(1).uid);
        assert Tvs.parse("").isEmpty() && Tvs.parse("nonsense").isEmpty();
        // Saved by an earlier build without an id: a Philips / LG is known by its pinned certificate.
        assert Tvs.parse("[{\"brand\":\"philips\",\"pin\":\"c\",\"id\":\"\"}]").get(0).id.equals("pin:c");

        // matchByIdNotIp: another home's TV at the same IP is not ours.
        List<Tvs.Saved> mine = new ArrayList<>(Arrays.asList(saved("samsung", "uuid:A", "192.168.1.221")));
        assert Tvs.match(mine, Arrays.asList(found("samsung", "192.168.1.221", "uuid:B"))).isEmpty();
        // Ours, moved to .50: found, and its IP is updated.
        List<Tvs.Saved> m = Tvs.match(mine, Arrays.asList(found("samsung", "192.168.1.50", "uuid:A")));
        assert m.size() == 1 && m.get(0).ip.equals("192.168.1.50") && mine.get(0).ip.equals("192.168.1.50");

        // A TV with a FireStick counts when either is found; the stick's IP follows it too.
        Tvs.Saved withStick = saved("lg", "uuid:L", "1.1.1.2");
        withStick.stickId = "S1";
        withStick.stickIp = "1.1.1.3";
        m = Tvs.match(Arrays.asList(withStick), Arrays.asList(found("firetv", "1.1.1.9", "S1")));
        assert m.size() == 1 && withStick.stickIp.equals("1.1.1.9");
        // A FireStick that doesn't trust us yet (id null) matches nothing.
        assert Tvs.match(Arrays.asList(withStick), Arrays.asList(found("firetv", "1.1.1.9", null))).isEmpty();

        // migratedPhilipsAdoptsId: the old Philips is known by its pinned certificate from the start; the
        // FireStick (no id yet) adopts the one at its old IP.
        Tvs.Saved old = Tvs.migrate("1.1.1.49", "1.1.1.221", "u", "p", "c", "m");
        assert old.brand.equals("philips") && old.id.equals("pin:c") && old.stickIp.equals("1.1.1.49") && old.input.isEmpty();
        assert old.auth.equals("u") && old.pass.equals("p") && old.pin.equals("c") && old.mac.equals("m");
        m = Tvs.match(Arrays.asList(old), Arrays.asList(found("philips", "1.1.1.221", "pin:c"), found("firetv", "1.1.1.49", "SER")));
        assert m.size() == 1 && old.stickId.equals("SER");
        // Moved before the first scan (seen live: the stick went from .49 to .15): the only FireStick here
        // is ours, as the old single-device scan assumed; the Philips by its certificate.
        Tvs.Saved moved = Tvs.migrate("1.1.1.49", "1.1.1.221", "u", "p", "c", "m");
        m = Tvs.match(Arrays.asList(moved), Arrays.asList(found("philips", "1.1.1.99", "pin:c"), found("firetv", "1.1.1.15", "SER")));
        assert m.size() == 1 && moved.ip.equals("1.1.1.99");
        assert moved.stickId.equals("SER") && moved.stickIp.equals("1.1.1.15");
        // Another home's Philips at the old IP is not ours (different certificate).
        Tvs.Saved other = Tvs.migrate("", "1.1.1.221", "u", "p", "c", "m");
        assert Tvs.match(Arrays.asList(other), Arrays.asList(found("philips", "1.1.1.221", "pin:zz"))).isEmpty();
        // Two FireSticks, neither at the old IP: can't tell which, adopt nothing.
        Tvs.Saved unsure = Tvs.migrate("1.1.1.49", "1.1.1.221", "", "", "", "");
        assert Tvs.match(Arrays.asList(unsure), Arrays.asList(found("firetv", "1.1.1.98", "A"),
            found("firetv", "1.1.1.99", "B"))).isEmpty();
        assert unsure.stickId.isEmpty();
        // A TV entry never adopts a stranger by IP (only FireSticks from the old settings do).
        Tvs.Saved noId = saved("samsung", "", "1.1.1.221");
        assert Tvs.match(Arrays.asList(noId), Arrays.asList(found("samsung", "1.1.1.221", "uuid:X"))).isEmpty();
        assert noId.id.isEmpty();

        // Never paired with the Philips: the old setup is just the FireStick.
        Tvs.Saved stickOnly = Tvs.migrate("1.1.1.49", "1.1.1.221", "", "", "", "");
        assert stickOnly.brand.equals("none") && stickOnly.stickIp.equals("1.1.1.49");

        // Adding: a TV not seen before is added.
        List<Tvs.Saved> list = new ArrayList<>();
        Tvs.Saved lg = saved("lg", "uuid:L", "1.1.1.2");
        lg.auth = "key1";
        Tvs.add(list, lg);
        assert list.size() == 1;
        // Adding the same TV again (paired again): updated in place, same uid, its add-ons and remote style kept.
        list.get(0).stickIp = "1.1.1.3";
        list.get(0).stickId = "S1";
        list.get(0).input = "tv";
        String uid = list.get(0).uid;
        Tvs.Saved again = saved("lg", "uuid:L", "1.1.1.5");
        again.auth = "key2";
        Tvs.add(list, again);
        assert list.size() == 1 && list.get(0).uid.equals(uid) && list.get(0).auth.equals("key2") && list.get(0).ip.equals("1.1.1.5");
        assert list.get(0).stickId.equals("S1") && list.get(0).input.equals("tv");

        // Two FireSticks at home: a FireStick is never attached by itself, it's saved on its own...
        list = new ArrayList<>(Arrays.asList(saved("samsung", "uuid:S", "1.1.1.20")));
        Tvs.add(list, Tvs.stick("1.1.1.21", "FS"));
        assert list.size() == 2 && list.get(1).brand.equals("none") && list.get(0).stickId.isEmpty();
        // ...and a FireStick or Sky box already saved (alone or on a TV) just gets its new IP.
        assert !Tvs.add(list, Tvs.stick("1.1.1.30", "FS")); // false: already saved, nothing new to bind
        assert list.size() == 2 && list.get(1).stickIp.equals("1.1.1.30");
        assert Tvs.add(list, Tvs.sky("1.1.1.40", "uuid:K")); // true: new
        Tvs.add(list, Tvs.sky("1.1.1.41", "uuid:K"));
        assert list.size() == 3 && list.get(2).skyIp.equals("1.1.1.41") && list.get(2).name.equals("Sky");
        assert Tvs.addOns(list, "stick").size() == 1 && Tvs.addOns(list, "sky").size() == 1;

        // Bound in TV info: the add-on moves onto the TV (its own card goes).
        String tvUid = list.get(0).uid;
        Tvs.bind(list, tvUid, list.get(1).uid);
        Tvs.bind(list, tvUid, list.get(1).uid); // now the Sky box (the list shrank)
        assert list.size() == 1 && list.get(0).stickId.equals("FS") && list.get(0).stickIp.equals("1.1.1.30");
        assert list.get(0).skyId.equals("uuid:K") && list.get(0).skyIp.equals("1.1.1.41");
        assert Tvs.addOns(list, "stick").isEmpty();
        // A second FireStick bound to the same TV: the first one goes back to being on its own.
        list.add(Tvs.stick("1.1.1.31", "FS2"));
        Tvs.bind(list, tvUid, list.get(1).uid);
        assert list.get(0).stickId.equals("FS2") && list.size() == 2 && list.get(1).stickId.equals("FS") && list.get(1).brand.equals("none");
        // Unbound: back on its own, after the TV.
        Tvs.unbind(list, tvUid, "sky");
        assert !list.get(0).hasSky() && list.get(1).skyId.equals("uuid:K") && list.size() == 3;
        // Removing a TV keeps its add-ons (on their own).
        Tvs.remove(list, tvUid);
        assert list.size() == 3 && list.get(0).stickId.equals("FS2") && list.get(0).brand.equals("none");

        // A saved Sky box is found again by its id.
        Tvs.Saved withSky = saved("samsung-legacy", "uuid:T", "1.1.1.14");
        withSky.skyIp = "1.1.1.50";
        withSky.skyId = "uuid:K";
        m = Tvs.match(Arrays.asList(withSky), Arrays.asList(found("sky", "1.1.1.51", "uuid:K")));
        assert m.size() == 1 && withSky.skyIp.equals("1.1.1.51");

        // Reordering: one step up or down; past either end nothing moves.
        List<Tvs.Saved> order = new ArrayList<>(Arrays.asList(saved("lg", "a", ""), saved("lg", "b", ""), saved("lg", "c", "")));
        Tvs.move(order, 2, -1);
        assert order.get(1).id.equals("c") && order.get(2).id.equals("b");
        Tvs.move(order, 0, -1);
        Tvs.move(order, 2, 1);
        assert order.get(0).id.equals("a") && order.get(1).id.equals("c") && order.get(2).id.equals("b");
    }
}
