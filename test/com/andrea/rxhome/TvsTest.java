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
        two.inputToTv = true;
        List<Tvs.Saved> back = Tvs.parse(Tvs.format(Arrays.asList(one, two)));
        assert back.size() == 2;
        assert back.get(0).auth.equals("tok") && back.get(0).pin.equals("ab12") && back.get(0).mac.equals("aa:bb:cc:dd:ee:ff");
        assert back.get(1).pass.equals("pass") && back.get(1).stickId.equals("G070") && back.get(1).inputToTv;
        assert !back.get(0).inputToTv;
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
        assert old.brand.equals("philips") && old.id.equals("pin:c") && old.stickIp.equals("1.1.1.49") && !old.inputToTv;
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
        Tvs.add(list, lg, Arrays.<Discovery.TvFound>asList());
        assert list.size() == 1;
        // Adding the same TV again (paired again): updated in place, same uid, its FireStick kept.
        list.get(0).stickIp = "1.1.1.3";
        list.get(0).stickId = "S1";
        list.get(0).inputToTv = true;
        String uid = list.get(0).uid;
        Tvs.Saved again = saved("lg", "uuid:L", "1.1.1.5");
        again.auth = "key2";
        Tvs.add(list, again, Arrays.<Discovery.TvFound>asList());
        assert list.size() == 1 && list.get(0).uid.equals(uid) && list.get(0).auth.equals("key2") && list.get(0).ip.equals("1.1.1.5");
        assert list.get(0).stickId.equals("S1") && list.get(0).inputToTv;

        // A FireStick added in a home with one saved TV (found here, no stick yet): attached to it.
        list = new ArrayList<>(Arrays.asList(saved("samsung", "uuid:S", "1.1.1.20")));
        Tvs.add(list, Tvs.stick("1.1.1.21", "FS"), Arrays.asList(found("samsung", "1.1.1.20", "uuid:S")));
        assert list.size() == 1 && list.get(0).stickId.equals("FS") && list.get(0).stickIp.equals("1.1.1.21");
        // ...but not to a TV that isn't here: saved alone.
        list = new ArrayList<>(Arrays.asList(saved("samsung", "uuid:S", "1.1.1.20")));
        Tvs.add(list, Tvs.stick("1.1.1.21", "FS"), Arrays.<Discovery.TvFound>asList());
        assert list.size() == 2 && list.get(1).brand.equals("none") && list.get(0).stickId.isEmpty();
        // Then the TV added later picks up the lone FireStick found here.
        list = new ArrayList<>(Arrays.asList(Tvs.stick("1.1.1.21", "FS")));
        Tvs.add(list, saved("philips", "uuid:P", "1.1.1.22"), Arrays.asList(found("firetv", "1.1.1.21", "FS")));
        assert list.size() == 1 && list.get(0).brand.equals("philips") && list.get(0).stickId.equals("FS");
        // A FireStick already saved: just its IP updated.
        Tvs.add(list, Tvs.stick("1.1.1.30", "FS"), Arrays.<Discovery.TvFound>asList());
        assert list.size() == 1 && list.get(0).stickIp.equals("1.1.1.30");
    }
}
