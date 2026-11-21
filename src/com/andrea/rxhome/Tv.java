package com.andrea.rxhome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One saved TV: its own API (Screen) and/or a FireStick on it. Power, volume and inputs go to the TV;
 * apps, RxFilms, Spotify and Claude FM to the stick; d-pad input to the stick unless the remote's switch
 * says TV. Either part may be missing.
 */
class Tv {

    private final Screen screen;
    private final FireTv stick;
    private volatile boolean inputToTv;

    Tv(Screen screen, FireTv stick, boolean inputToTv) {
        this.screen = screen;
        this.stick = stick;
        this.inputToTv = inputToTv;
    }

    boolean hasScreen() {
        return screen != null;
    }

    boolean hasStick() {
        return stick != null;
    }

    /** The remote's FireStick | TV switch: only when there are both. */
    boolean showSwitch() {
        return screen != null && stick != null;
    }

    boolean inputToTv() {
        return inputToTv;
    }

    void setInputToTv(boolean toTv) {
        inputToTv = toTv;
    }

    /** "films" (RxFilms / Spotify / Claude FM), "volume" (a level), "mute", "apps", "power". */
    boolean can(String what) {
        switch (what) {
            case "films": return stick != null;
            case "volume":
            case "mute": return screen != null && screen.can(what);
            case "apps": return stick != null || screen != null && screen.can("apps");
            default: return screen != null || stick != null;
        }
    }

    void onKeyPrompt(Runnable r) {
        if (stick != null) stick.onKeyPrompt(r);
    }

    void close() {
        if (stick != null) stick.close();
    }

    // --- state ---

    /** The device we talk to: the stick if there is one, else the TV's own word. */
    State state() {
        if (stick != null) return stick.state();
        Boolean on = screen == null ? null : screen.isOn();
        return on == null ? State.UNREACHABLE : on ? State.ON : State.OFF;
    }

    /** Like state(), but says why (Adb.AdbAuthException: the TV's ADB prompt hasn't been accepted). */
    State checkedState() throws IOException {
        if (stick != null) return stick.checkedState();
        State s = state();
        if (s == State.UNREACHABLE) throw new IOException(screen == null ? "Add a TV in Settings" : "The TV doesn't answer");
        return s;
    }

    /**
     * The TV's power (s = state(), already known): its own word if it can tell, else what it announced
     * over HDMI-CEC to the stick, else s. Stick unreachable: away from home, unknown.
     */
    State tvState(State s) {
        if (stick != null && s == State.UNREACHABLE) return State.UNREACHABLE;
        Boolean on = screen == null ? null : screen.isOn();
        if (on == null && stick != null) on = stick.cecTvOn();
        return on == null ? s : on ? State.ON : State.OFF;
    }

    void togglePower(State s) throws IOException {
        if (tvState(s) == State.ON) sleep();
        else wake();
    }

    /** TV on (its API, then Wake-on-LAN), then the stick. A TV that doesn't answer doesn't stop the stick. */
    void wake() throws IOException {
        if (screen != null) {
            try {
                screen.powerOn();
            } catch (IOException e) {
                if (stick == null) throw e;
                log("power on", e);
            }
        }
        if (stick != null) stick.wake();
    }

    void sleep() throws IOException {
        if (screen != null) {
            try {
                screen.powerOff();
            } catch (IOException e) {
                if (stick == null) throw e;
                log("standby", e); // already off, or not on this network: the stick still sleeps
            }
        }
        if (stick != null) stick.sleep();
    }

    // --- input (the switch decides) ---

    private boolean toStick() {
        return stick != null && (screen == null || !inputToTv);
    }

    void key(int code) throws IOException {
        if (toStick()) stick.key(code);
        else need().key(code);
    }

    void keyDown(int code) throws IOException {
        if (toStick()) stick.keyDown(code);
        else need().key(code);
    }

    /** TV: each repeat is one more press (their APIs have no hold). */
    void keyRepeat(int code, int n) throws IOException {
        if (toStick()) stick.keyRepeat(code, n);
        else need().key(code);
    }

    void keyUp(int code) {
        if (toStick()) stick.keyUp(code);
    }

    // --- always the TV ---

    /** Volume up / down / mute keys (24, 25, 164): the TV's API, or the stick's keys (HDMI-CEC) without one. */
    void volumeKey(int code) throws IOException {
        if (screen != null) screen.key(code);
        else if (stick != null) stick.key(code);
        else need();
    }

    void tvVolume(int level) throws IOException {
        if (screen == null) throw new IOException("Pair the TV in Settings to control its volume");
        screen.setVolume(level);
    }

    void tvMute() throws IOException {
        if (screen == null) throw new IOException("Pair the TV in Settings to control its volume");
        screen.mute();
    }

    // --- apps: the stick, else the TV's own ---

    void launch(String app) throws IOException {
        if (stick != null) stick.launch(app);
        else need().launch(app);
    }

    /** {id, name}: the stick's installed packages, or the TV's own apps. */
    List<String[]> apps() throws IOException {
        if (stick == null) return need().apps();
        List<String[]> out = new ArrayList<>();
        for (String pkg : stick.apps()) out.add(new String[] {pkg, Scenes.apps().getOrDefault(pkg, pkg)});
        return out;
    }

    void view(String uri, String pkg) throws IOException {
        films().view(uri, pkg);
    }

    void claudeFm() throws IOException {
        films().claudeFm();
    }

    void openItem(String component, String itemId) throws IOException {
        films().openItem(component, itemId);
    }

    private FireTv films() {
        if (stick == null) throw new UnsupportedOperationException("Needs a FireStick on this TV");
        return stick;
    }

    private Screen need() throws IOException {
        if (screen == null) throw new IOException("Add a TV in Settings");
        return screen;
    }

    private static void log(String what, Exception e) {
        System.err.println("rxhome-tv: " + what + " failed: " + e);
    }
}
