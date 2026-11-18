package com.andrea.rxhome;

import java.util.Arrays;
import java.util.List;

public class TvsTest {
    public static void main(String[] a) {
        // Nothing saved yet: today's single setup becomes the first TV (its pairing keeps the old settings).
        List<Tvs.Tv> l = Tvs.orLegacy("", "192.168.1.49", "192.168.1.221");
        assert l.size() == 1 && l.get(0).id.equals("tv1") && l.get(0).name.equals("Tv")
            && l.get(0).stick.equals("192.168.1.49") && l.get(0).philips.equals("192.168.1.221");
        assert Tvs.key("tv1", "philips_user").equals("philips_user") && Tvs.key("tv2", "philips_user").equals("philips_user_tv2");

        // One TV per line: id|name|stick|philips; either part may be missing.
        String text = "tv1|Salotto|192.168.1.49|192.168.1.221\ntv2|Camera||192.168.1.80\ntv3|Studio|192.168.1.90|";
        List<Tvs.Tv> tvs = Tvs.orLegacy(text, "x", "y");
        assert tvs.size() == 3 && tvs.get(1).stick.isEmpty() && tvs.get(2).philips.isEmpty();
        assert Tvs.format(tvs).equals(text) : Tvs.format(tvs);
        assert Tvs.parse("junk\n\ntv9|Ok||1.2.3.4").size() == 1;               // broken lines skipped
        assert Tvs.newId(tvs).equals("tv4");
        assert Tvs.find(tvs, "camera").id.equals("tv2") && Tvs.find(tvs, "tv3").name.equals("Studio");
        assert Tvs.find(tvs, "Garage") == null;
        // Names can't break the format.
        assert Tvs.clean(" Big | room\n") .equals("Big room") && Tvs.clean("  ").equals("TV");

        // Settings' row line: the devices' own names (their address until a scan has read the name).
        java.util.function.Function<String, String> names = ip -> ip.equals("192.168.1.49") ? "Fire TV Stick di Andrea"
            : ip.equals("192.168.1.221") ? "55OLED770/12" : ip;
        assert Tvs.describe(tvs.get(0), true, names).equals("Fire TV Stick di Andrea · 55OLED770/12 (paired)")
            : Tvs.describe(tvs.get(0), true, names);
        assert Tvs.describe(tvs.get(1), false, names).equals("192.168.1.80 (not paired): power and volume only");
        assert Tvs.describe(tvs.get(2), false, names).equals("192.168.1.90 · power and volume through it");
        assert Tvs.describe(new Tvs.Tv("tv5", "New", "", ""), false, names).equals("No devices yet");
        assert Arrays.asList(Tvs.names(tvs)).equals(Arrays.asList("Salotto", "Camera", "Studio"));
    }
}
