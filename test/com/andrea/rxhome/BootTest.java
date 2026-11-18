package com.andrea.rxhome;

public class BootTest {
    public static void main(String[] a) {
        // Remembered boot times: the last 5, from the power press to Jellyfin answering.
        String h = "";
        for (long ms : new long[] {40_000, 50_000, 45_000, 60_000, 44_000, 46_000}) h = Boot.record(h, ms);
        assert h.equals("50000,45000,60000,44000,46000") : h;
        // A press that never finished (over 15 min) or an instant one isn't a boot time.
        assert Boot.record(h, 20 * 60_000).equals(h) && Boot.record(h, 0).equals(h);
        assert Boot.record("junk,40000", 50_000).equals("40000,50000") : Boot.record("junk,40000", 50_000);

        // The estimate is the typical boot (median), so one slow boot doesn't skew it.
        assert Boot.estimate("50000,45000,60000,44000,46000") == 46_000;
        assert Boot.estimate("") == 0;

        // What the card says while powering on.
        assert Boot.label(0, 0).equals("Powering on…");                          // no history yet
        assert Boot.label(10_000, 46_000).equals("Powering on… ~36 s left");
        assert Boot.label(44_500, 46_000).equals("Powering on… ~2 s left");     // rounds up, never "0 s"
        assert Boot.label(50_000, 46_000).equals("Powering on… taking longer than usual");
        assert Boot.label(130_000, 200_000).equals("Powering on… ~1 min 10 s left");
        // Gives up following after 10 minutes.
        assert !Boot.expired(9 * 60_000) && Boot.expired(11 * 60_000);
    }
}
