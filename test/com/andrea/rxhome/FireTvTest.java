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

    /** A stick whose HDMI-CEC log reads `dump`. */
    static FireTv stickWithCec(String dump) {
        return new FireTv(null) {
            @Override synchronized void key(int code) { calls.add("stick " + code); }
            @Override String cecLog() { return dump; }
        };
    }

    public static void main(String[] a) throws Exception {
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
        // Switched on with its own remote, a TV often sends no power report, just "show this input" messages
        // (Set Stream Path, Routing Change, Request Active Source): a TV in standby doesn't send those.
        String streamPath = "    [R] time=2026-11-18 20:01:02 message=<Set Stream Path> 0F:86:30:00\n";
        String routing = "    [R] time=2026-11-18 20:01:03 message=<Routing Change> 0F:80:00:00:30:00\n";
        String askSource = "    [R] time=2026-11-18 20:01:04 message=<Request Active Source> 0F:85\n";
        assert FireTv.parseTvPower(standby + streamPath) == Boolean.TRUE;
        assert FireTv.parseTvPower(standby + routing) == Boolean.TRUE;
        assert FireTv.parseTvPower(standby + askSource) == Boolean.TRUE;
        assert FireTv.parseTvPower(streamPath + standby) == Boolean.FALSE;   // then it went off
        // Only the TV's own messages count (address 0), not the stick's.
        assert FireTv.parseTvPower(standby + "    [S] time=2026-11-18 20:01:05 message=<Active Source> 4F:82:30:00\n") == Boolean.FALSE;
        // The scene signal for the launcher: one explicit broadcast, names quoted for the shell.
        assert FireTv.signalCommand("swap", "Movie night", "Kid's room", 1500).equals(
            "am broadcast -a com.andrea.rxhome.SCENE -p com.andrea.tvlauncher --es event 'swap' --es scene 'Movie night'"
                + " --ei fade_ms 1500 --es from 'Kid'\\''s room'") : FireTv.signalCommand("swap", "Movie night", "Kid's room", 1500);

        // The stick reads it from its CEC log (routing by it: TvTest).
        assert stickWithCec(standby).cecTvOn() == Boolean.FALSE;
        assert stickWithCec("").cecTvOn() == null;

        // Nothing listens on port 9: unreachable, and fast (the home screen never waits on a dead device).
        long t0 = System.currentTimeMillis();
        assert Server.status("http://127.0.0.1:9") == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2000;
        // Unroutable address (phone away from home): still within the 1.5 s budget.
        t0 = System.currentTimeMillis();
        assert Server.status("http://192.168.1.250:8096") == State.UNREACHABLE;
        assert System.currentTimeMillis() - t0 < 2500 : System.currentTimeMillis() - t0;


        // Another home's FireStick at the saved IP: not ours, so not used.
        try {
            FireTv.parseState("OTHER\n  mWakefulness=Awake\n", "SER");
            throw new AssertionError("a stranger's FireStick was accepted");
        } catch (java.io.IOException e) {
            assert e.getMessage().contains("FireStick") : e.getMessage();
        }
        assert FireTv.parseState("SER\n  mWakefulness=Awake\n", "SER") == State.ON;
        assert FireTv.parseState("ANY\n  mWakefulness=Asleep\n", "") == State.OFF; // no serial saved yet

        assert FireTv.parseAwake("  mWakefulness=Awake\n") == State.ON;
        assert FireTv.parseAwake("  mWakefulness=Asleep\n") == State.OFF;
        assert FireTv.parseAwake("  mWakefulness=Dozing\n") == State.OFF;

        // Stats answer but Jellyfin doesn't yet: the server is booting.
        assert Server.label(State.ON, true, true).equals("online") && Server.label(State.ON, false, false).equals("online");
        assert Server.label(State.UNREACHABLE, true, false).equals("starting");
        // Home devices answer (home Wi-Fi or the tunnel) but the server doesn't: it's off.
        assert Server.label(State.UNREACHABLE, false, true).equals("off");
        // Nothing at home answers: can't tell.
        assert Server.label(State.UNREACHABLE, false, false).equals("unknown");
        // For "server must be on": starting counts as on (pressing now would switch it off mid-boot).
        assert Server.effective(State.UNREACHABLE, true) == State.ON;
        assert Server.effective(State.UNREACHABLE, false) == State.UNREACHABLE;

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
