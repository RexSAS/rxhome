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
