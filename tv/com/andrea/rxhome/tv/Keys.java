package com.andrea.rxhome.tv;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Runs on the FireStick under the adb shell user (app_process), not in the phone app: reads one keycode
 * per line from stdin and injects it as a d-pad key, like `input dpad keyevent` but without starting a
 * new Java runtime for every press. Answers "ok" per key so the phone knows it landed.
 */
public final class Keys {
    public static void main(String[] args) throws Exception {
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        Object manager = im.getMethod("getInstance").invoke(null);
        Method inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("ready");
        System.out.flush();
        for (String line; (line = in.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int code = Integer.parseInt(line);
            long now = SystemClock.uptimeMillis();
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent e = new KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_DPAD);
                inject.invoke(manager, e, 2); // 2 = wait for the key to be handled, as `input` does
            }
            System.out.println("ok");
            System.out.flush();
        }
    }
}
