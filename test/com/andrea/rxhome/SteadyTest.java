package com.andrea.rxhome;

public class SteadyTest {
    public static void main(String[] a) {
        // Overlapping checks: only the newest one's answer counts (an older one finishing last is ignored).
        Steady s = new Steady();
        int first = s.next(), second = s.next();
        assert s.current(second) && !s.current(first);

        // The stats line: one missed reading keeps the last stats; two in a row hide it.
        assert "CPU 5%".equals(s.stats("CPU 5%"));
        assert "CPU 5%".equals(s.stats(null));   // one miss: keep showing
        assert s.stats(null) == null;            // two misses: hide
        assert "CPU 9%".equals(s.stats("CPU 9%"));
        assert "CPU 9%".equals(s.stats(null));   // the count starts over after a good reading
    }
}
