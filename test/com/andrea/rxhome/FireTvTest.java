package com.andrea.rxhome;

import java.io.File;

public class FireTvTest {
    public static void main(String[] a) throws Exception {
        // Nothing listens on port 9: unreachable, and fast (the home screen never waits on a dead device).
        long t0 = System.currentTimeMillis();
        assert Server.status("http://127.0.0.1:9") == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2000;
        // Unroutable address (phone away from home): still within the 1.5 s budget.
        t0 = System.currentTimeMillis();
        assert Server.status("http://192.168.1.250:8096") == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2500 : System.currentTimeMillis() - t0;

        // The TV's CEC power reports, as dumpsys hdmi_control prints them; the last one wins.
        String on = "    [R] time=2026-11-17 19:52:21 message=<Report Power Status> 04:90:00\n";
        String standby = "    [R] time=2026-11-17 19:58:02 message=<Report Power Status> 04:90:01\n";
        assert FireTv.parseTvOn(on);
        assert !FireTv.parseTvOn(standby);
        assert !FireTv.parseTvOn(on + standby);
        assert FireTv.parseTvOn(standby + on);
        assert !FireTv.parseTvOn("");
        // Our own outgoing request is not the TV's answer.
        assert !FireTv.parseTvOn("    [S] time=2026-11-17 19:52:20 message=<Report Power Status> 40:90:00\n");

        assert FireTv.parseAwake("  mWakefulness=Awake\n") == State.ON;
        assert FireTv.parseAwake("  mWakefulness=Asleep\n") == State.OFF;
        assert FireTv.parseAwake("  mWakefulness=Dozing\n") == State.OFF;

        String server = System.getenv("RXHOME_SERVER");
        if (server != null) assert Server.status(server) == State.ON;

        String host = System.getenv("RXHOME_ADB");
        File priv = new File(System.getProperty("user.home"), ".android/adbkey");
        if (host != null && priv.exists()) {
            FireTv tv = new FireTv(new Adb(host, 5555, AdbTest.pem(priv)), null, null);
            assert tv.state() != State.UNREACHABLE;
        }
        FireTv away = new FireTv(new Adb("192.168.1.250", 5555, Adb.generateKey()), null, null);
        t0 = System.currentTimeMillis();
        assert away.state() == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2000 : "grey took " + (System.currentTimeMillis() - t0) + " ms";
    }
}
