package com.andrea.rxhome.tv;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs on the FireStick under the adb shell user (app_process), not in the phone app: injects d-pad keys
 * without starting a new Java runtime for every press, as `input dpad keyevent` would. One command per
 * line on stdin, answered with "ok":
 *   "<code>"       press (down + up)
 *   "d <code>"     key down (held)
 *   "r <code> <n>" n-th repeat while held (the first carries FLAG_LONG_PRESS, like a real remote)
 *   "u <code>"     key up
 */
public final class Keys {

    private static Object manager;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        manager = im.getMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        Map<Integer, Long> downAt = new HashMap<>();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("ready");
        System.out.flush();
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.trim().split(" ");
            if (p[0].isEmpty()) continue;
            long now = SystemClock.uptimeMillis();
            if (p.length == 1) {
                int code = Integer.parseInt(p[0]);
                send(now, now, KeyEvent.ACTION_DOWN, code, 0, 0);
                send(now, now, KeyEvent.ACTION_UP, code, 0, 0);
            } else {
                int code = Integer.parseInt(p[1]);
                if (p[0].equals("d")) {
                    downAt.put(code, now);
                    send(now, now, KeyEvent.ACTION_DOWN, code, 0, 0);
                } else if (p[0].equals("r")) {
                    int n = Integer.parseInt(p[2]);
                    long down = downAt.containsKey(code) ? downAt.get(code) : now;
                    send(down, now, KeyEvent.ACTION_DOWN, code, n, n == 1 ? KeyEvent.FLAG_LONG_PRESS : 0);
                } else if (p[0].equals("u")) {
                    Long down = downAt.remove(code);
                    send(down != null ? down : now, now, KeyEvent.ACTION_UP, code, 0, 0);
                }
            }
            System.out.println("ok");
            System.out.flush();
        }
    }

    private static void send(long downTime, long time, int action, int code, int repeat, int flags) throws Exception {
        KeyEvent e = new KeyEvent(downTime, time, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
            InputDevice.SOURCE_DPAD);
        inject.invoke(manager, e, 2); // 2 = wait for the key to be handled, as `input` does
    }
}
