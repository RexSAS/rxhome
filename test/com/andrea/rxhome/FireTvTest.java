package com.andrea.rxhome;

import java.io.File;

public class FireTvTest {
    interface Io { void go() throws Exception; }

    static void run(Io io) {
        try {
            io.go();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static final java.util.List<String> calls = new java.util.ArrayList<>();

    static FireTv stick() {
        return new FireTv(null) {
            @Override synchronized void key(int code) { calls.add("stick " + code); }
        };
    }

    static Philips philips(boolean broken) {
        return new Philips("x", "u", "p", "c") {
            @Override void key(String k) throws java.io.IOException {
                if (broken) throw new java.io.IOException("TV asleep");
                calls.add("tv " + k);
            }
        };
    }

    /** A stick whose HDMI-CEC log reads `dump`. */
    static FireTv stickWithCec(String dump) {
        return new FireTv(null) {
            @Override synchronized void key(int code) { calls.add("stick " + code); }
            @Override String cecLog() { return dump; }
        };
    }

    public static void main(String[] a) throws Exception {
        // With the Philips paired, TV off/on switch the TV itself, then the stick.
        FireTv t = stick();
        t.setPhilips(philips(false), null);
        t.sleep();
        assert calls.equals(java.util.Arrays.asList("tv Standby", "stick 223")) : calls;
        calls.clear();
        t.wake();
        assert calls.equals(java.util.Arrays.asList("tv PowerOn", "stick 224")) : calls;
        calls.clear();
        t.tvKey("VolumeUp");
        assert calls.equals(java.util.Arrays.asList("tv VolumeUp")) : calls;
        // TV unreachable (e.g. already in standby): the stick still sleeps.
        calls.clear();
        t.setPhilips(philips(true), null);
        t.sleep();
        assert calls.equals(java.util.Arrays.asList("stick 223")) : calls;
        // The TV's power, from what it announces over HDMI-CEC (lines as the stick logs them, 2026-11-17).
        // Its network API can't tell: it answers the same on and in standby.
        String standby = "    [R] time=2026-11-17 21:42:28 message=<Standby> 0F:36\n";
        String reportOn = "    [R] time=2026-11-17 21:43:24 message=<Report Power Status> 04:90:00\n";
        String reportStandby = "    [R] time=2026-11-17 21:50:00 message=<Report Power Status> 04:90:01\n";
        assert FireTv.parseTvPower(standby) == Boolean.FALSE;
        assert FireTv.parseTvPower(reportOn) == Boolean.TRUE;
        assert FireTv.parseTvPower(standby + reportOn) == Boolean.TRUE;
        assert FireTv.parseTvPower(reportOn + standby) == Boolean.FALSE;
        assert FireTv.parseTvPower(reportOn + reportStandby) == Boolean.FALSE;
        // Our own (the stick's) messages don't count, nor does nothing at all.
        assert FireTv.parseTvPower("    [S] time=2026-11-17 21:43:24 message=<Report Power Status> 40:90:00\n") == null;
        assert FireTv.parseTvPower("") == null;

        // Power button by the TV's state: on → Standby key; off → PowerOn key; the stick follows.
        for (String dump : new String[] {reportOn, standby}) {
            calls.clear();
            FireTv p = stickWithCec(dump);
            p.setPhilips(philips(false), null);
            assert p.tvState(State.ON) == (dump == reportOn ? State.ON : State.OFF);
            p.togglePower(State.ON);
            assert calls.equals(dump == reportOn
                ? java.util.Arrays.asList("tv Standby", "stick 223")
                : java.util.Arrays.asList("tv PowerOn", "stick 224")) : calls;
        }
        // Away from home (stick unreachable): unknown, not "off".
        assert stickWithCec(standby).tvState(State.UNREACHABLE) == State.UNREACHABLE;
        // No CEC news yet: the stick's own state stands in.
        assert stickWithCec("").tvState(State.OFF) == State.OFF;
        calls.clear();

        // Not paired: volume says what to do.
        FireTv bare = stick();
        try {
            bare.tvKey("VolumeUp");
            throw new AssertionError("volume without a paired TV");
        } catch (java.io.IOException e) {
            assert e.getMessage().contains("Pair") : e.getMessage();
        }

        // Nothing listens on port 9: unreachable, and fast (the home screen never waits on a dead device).
        long t0 = System.currentTimeMillis();
        assert Server.status("http://127.0.0.1:9") == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2000;
        // Unroutable address (phone away from home): still within the 1.5 s budget.
        t0 = System.currentTimeMillis();
        assert Server.status("http://192.168.1.250:8096") == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2500 : System.currentTimeMillis() - t0;


        assert FireTv.parseAwake("  mWakefulness=Awake\n") == State.ON;
        assert FireTv.parseAwake("  mWakefulness=Asleep\n") == State.OFF;
        assert FireTv.parseAwake("  mWakefulness=Dozing\n") == State.OFF;

        // LAN address first, then the public one (rxfilms.rxsas.it): any answer means online.
        assert Server.status("http://127.0.0.1:9", "http://127.0.0.1:9") == State.UNREACHABLE;
        String server = System.getenv("RXHOME_SERVER");
        if (server != null) {
            assert Server.status(server) == State.ON;
            assert Server.status("http://127.0.0.1:9", server) == State.ON : "fallback not tried";
        }

        String host = System.getenv("RXHOME_ADB");
        File priv = new File(System.getProperty("user.home"), ".android/adbkey");
        if (host != null && priv.exists()) {
            FireTv tv = new FireTv(new Adb(host, 5555, AdbTest.pem(priv)));
            assert tv.state() != State.UNREACHABLE;

            // Fast keys: the helper stays running on the stick, so after the first key (which pushes and
            // starts it) each press is one round trip, not a ~1 s `input` JVM start. Keycode 0 is a no-op.
            File dex = new File("build/rxhome-keys.dex");
            assert dex.exists() : "test.sh should have built " + dex;
            FireTv fast = new FireTv(new Adb(host, 5555, AdbTest.pem(priv)),
                new Adb(host, 5555, AdbTest.pem(priv)), java.nio.file.Files.readAllBytes(dex.toPath()));
            fast.key(0);
            for (int i = 0; i < 3; i++) {
                long k0 = System.currentTimeMillis();
                fast.key(0);
                long took = System.currentTimeMillis() - k0;
                assert took < 150 : "key took " + took + " ms";
            }
            // Holding a key: down, repeats while held, up on release, each a single fast round trip.
            for (Runnable step : new Runnable[] {
                () -> run(() -> fast.keyDown(0)), () -> run(() -> fast.keyRepeat(0, 1)), () -> run(() -> fast.keyUp(0))}) {
                long k0 = System.currentTimeMillis();
                step.run();
                long took = System.currentTimeMillis() - k0;
                assert took < 150 : "hold step took " + took + " ms";
            }
            // On the TV a hold looks like the real remote's: DOWN, DOWN (repeat), UP. Keycode 4 (BACK)
            // is logged by Fire OS; a back press on the home screen does nothing visible.
            Adb probe = new Adb(host, 5555, AdbTest.pem(priv));
            probe.shell("logcat -c", 3000);
            fast.keyDown(4);
            fast.keyRepeat(4, 1);
            fast.keyUp(4);
            Thread.sleep(300);
            String log = probe.shell("logcat -d | grep 'Received Key name: BACK'", 3000);
            probe.close();
            assert log.split("action: 0").length - 1 == 2 && log.split("action: 1").length - 1 == 1 : log;
            fast.close();
            // Key not accepted yet: the screen needs the reason. Pops a TV prompt, so opt-in only.
            if (System.getenv("RXHOME_ADB_PROMPT") != null) try {
                new FireTv(new Adb(host, 5555, Adb.generateKey())).checkedState();
                throw new AssertionError("unknown key accepted");
            } catch (Adb.AdbAuthException expected) {
                // ok
            }
        }
        FireTv away = new FireTv(new Adb("192.168.1.250", 5555, Adb.generateKey()));
        t0 = System.currentTimeMillis();
        assert away.state() == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2000 : "grey took " + (System.currentTimeMillis() - t0) + " ms";
    }
}
