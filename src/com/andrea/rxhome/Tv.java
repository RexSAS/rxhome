package com.andrea.rxhome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One saved TV: its own API (Screen) and what's bound to it, a FireStick and/or a Sky box. Power,
 * volume and inputs go to the TV; apps, RxFilms, Spotify and Claude FM to the stick; the d-pad and
 * touchpad to whichever the remote's style says ("tv", "stick", "sky"). Any part may be missing.
 */
class Tv {

    private final Screen screen;
    private final FireTv stick;
    private final Sky sky;
    private volatile String input;

    Tv(Screen screen, FireTv stick, Sky sky, String input) {
        this.screen = screen;
        this.stick = stick;
        this.sky = sky;
        this.input = input == null ? "" : input;
    }

    /** The remote's styles this TV has, in button order: "tv", "stick", "sky". */
    List<String> modes() {
        List<String> m = new ArrayList<>();
        if (screen != null) m.add("tv");
        if (stick != null) m.add("stick");
        if (sky != null) m.add("sky");
        return m;
    }

    /** The style in use: the one picked, if this TV still has it; else the FireStick, the Sky box, the TV. */
    String mode() {
        List<String> m = modes();
        if (m.contains(input)) return input;
        return stick != null ? "stick" : sky != null ? "sky" : "tv";
    }

    void setMode(String mode) {
        input = mode;
    }

    boolean hasSky() {
        return sky != null;
    }

    /** A Sky key by its Sky code (channel up, colours, record...): only in Sky style. */
    void skyKey(int code) throws IOException {
        if (sky == null) throw new IOException("No Sky box on this TV: bind one in Settings → TVs");
        sky.press(code);
    }

    boolean hasScreen() {
        return screen != null;
    }

    boolean hasStick() {
        return stick != null;
    }

    /** Only the stick out of standby, the TV left as it is. */
    void wakeStick() throws IOException {
        stick.wake();
    }

    /** The remote's style buttons: only when there's something besides the TV. */
    boolean showSwitch() {
        return modes().size() > 1;
    }

    /** "films" (RxFilms / Spotify / Claude FM), "volume" (a level), "mute", "apps", "power". */
    boolean can(String what) {
        switch (what) {
            case "films": return stick != null;
            case "volume":
            case "mute": return screen != null && screen.can(what);
            case "apps": return stick != null || screen != null && screen.can("apps");
            default: return screen != null || stick != null || sky != null;
        }
    }

    void onKeyPrompt(Runnable r) {
        if (stick != null) stick.onKeyPrompt(r);
    }

    void close() {
        if (stick != null) stick.close();
    }

    // --- state ---

    /** The device we talk to: the stick if there is one, else the TV's own word, else the Sky box answering. */
    State state() {
        if (stick != null) return stick.state();
        if (screen == null && sky != null) return sky.answers() ? State.ON : State.UNREACHABLE;
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

    private volatile boolean lastWasWake;

    /** A TV alone that can't tell its power (Philips) alternates, starting with on, so off is always reachable. */
    void togglePower(State s) throws IOException {
        if (screen == null && stick == null && sky != null) { // a Sky box alone: its own power key
            sky.press(Sky.POWER);
            return;
        }
        State t = tvState(s);
        boolean off = t == State.ON || t == State.UNREACHABLE && screen != null && stick == null && lastWasWake;
        if (off) sleep();
        else wake();
    }

    /** TV on (its API, then Wake-on-LAN), then the stick. A TV that doesn't answer doesn't stop the stick. */
    void wake() throws IOException {
        lastWasWake = true;
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
        lastWasWake = false;
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

    // --- input (the remote's style decides) ---

    void key(int code) throws IOException {
        switch (mode()) {
            case "stick": stick.key(code); break;
            case "sky": sky.key(code); break;
            default: need().key(code);
        }
    }

    /** Types into the field selected on the FireStick (the remote shows its text bar in FireStick style only). */
    void type(String text) throws IOException {
        if (stick == null) throw new IOException("Typing needs a FireStick");
        stick.type(text);
    }

    void keyDown(int code) throws IOException {
        if (mode().equals("stick")) stick.keyDown(code);
        else key(code);
    }

    /** TV and Sky: each repeat is one more press (no hold there). */
    void keyRepeat(int code, int n) throws IOException {
        if (mode().equals("stick")) stick.keyRepeat(code, n);
        else key(code);
    }

    void keyUp(int code) {
        if (mode().equals("stick")) stick.keyUp(code);
    }

    // --- always the TV ---

    /** Keys for the TV itself, volume up / down / mute / source (24, 25, 164, 178): the TV's API, or the stick's keys (HDMI-CEC) without one. */
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
