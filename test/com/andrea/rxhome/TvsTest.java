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
        assert Tvs.parse("").isEmpty() && Tvs.parse("nonsense").isEmpty();

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

        // migratedPhilipsAdoptsId: the old setup has no ids; the first scan adopts them by IP, once.
        Tvs.Saved old = Tvs.migrate("1.1.1.49", "1.1.1.221", "u", "p", "c", "m");
        assert old.brand.equals("philips") && old.stickIp.equals("1.1.1.49") && !old.inputToTv;
        assert old.auth.equals("u") && old.pass.equals("p") && old.pin.equals("c") && old.mac.equals("m");
        m = Tvs.match(Arrays.asList(old), Arrays.asList(found("philips", "1.1.1.221", "uuid:P"), found("firetv", "1.1.1.49", "SER")));
        assert m.size() == 1 && old.id.equals("uuid:P") && old.stickId.equals("SER");
        Tvs.Saved other = Tvs.migrate("1.1.1.49", "1.1.1.221", "u", "p", "c", "m");
        assert Tvs.match(Arrays.asList(other), Arrays.asList(found("philips", "1.1.1.99", "uuid:P"))).isEmpty();

        // Never paired with the Philips: the old setup is just the FireStick.
        Tvs.Saved stickOnly = Tvs.migrate("1.1.1.49", "1.1.1.221", "", "", "", "");
        assert stickOnly.brand.equals("none") && stickOnly.stickIp.equals("1.1.1.49");
    }
}
