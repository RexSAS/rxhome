package com.andrea.rxhome;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class TvTest {

    static final List<String> calls = new java.util.ArrayList<>();

    /** A TV whose own API records calls; broken = doesn't answer. on = what it says about its power. */
    static Screen screen(boolean broken, Boolean on) {
        return new Screen() {
            void maybe(String what) throws IOException {
                if (broken) throw new IOException("TV asleep");
                calls.add("tv " + what);
            }
            @Override public void key(int k) throws IOException { maybe("key " + k); }
            @Override public void setVolume(int l) throws IOException { maybe("volume " + l); }
            @Override public void mute() throws IOException { maybe("mute"); }
            @Override public void powerOff() throws IOException { maybe("off"); }
            @Override public void powerOn() throws IOException { maybe("on"); }
            @Override public Boolean isOn() { return on; }
            @Override public List<String[]> apps() { return Arrays.asList(new String[][] {{"netflix", "Netflix"}}); }
            @Override public void launch(String id) throws IOException { maybe("launch " + id); }
            @Override public boolean can(String what) { return true; }
        };
    }

    static FireTv stick(String cec) {
        return new FireTv(null) {
            @Override synchronized void key(int code) { calls.add("stick " + code); }
            @Override synchronized void keyDown(int code) { calls.add("stick down " + code); }
            @Override synchronized void keyUp(int code) { calls.add("stick up " + code); }
            @Override void launch(String pkg) { calls.add("stick launch " + pkg); }
            @Override String cecLog() { return cec; }
        };
    }

    static void expect(String... want) {
        assert calls.equals(Arrays.asList(want)) : calls;
        calls.clear();
    }

    public static void main(String[] a) throws Exception {
        String standby = "    [R] time=2026-11-17 21:42:28 message=<Standby> 0F:36\n";
        String reportOn = "    [R] time=2026-11-17 21:43:24 message=<Report Power Status> 04:90:00\n";

        // Both: input goes to the FireStick by default; the switch sends it to the TV.
        Tv both = new Tv(screen(false, null), stick(""), false);
        assert both.showSwitch() && !both.inputToTv();
        both.key(19);
        both.keyDown(20);
        both.keyUp(20);
        expect("stick 19", "stick down 20", "stick up 20");
        both.setInputToTv(true);
        both.key(19);
        both.keyDown(20);
        both.keyRepeat(20, 1);
        both.keyUp(20);
        expect("tv key 19", "tv key 20", "tv key 20");
        // Volume always to the TV, apps always to the stick, whatever the switch says.
        both.volumeKey(24);
        both.launch("com.x");
        expect("tv key 24", "stick launch com.x");
        both.setInputToTv(false);
        both.volumeKey(25);
        expect("tv key 25");

        // Power: the TV first, then the stick.
        both.sleep();
        expect("tv off", "stick 223");
        both.wake();
        expect("tv on", "stick 224");
        // powerOnWhenScreenUnreachable: the stick still wakes, no error.
        Tv deaf = new Tv(screen(true, null), stick(""), false);
        deaf.wake();
        expect("stick 224");
        deaf.sleep();
        expect("stick 223");

        // The TV's power: its own word if it has one, else HDMI-CEC through the stick, else the stick's state.
        assert new Tv(screen(false, true), stick(standby), false).tvState(State.ON) == State.ON;
        assert new Tv(screen(false, null), stick(standby), false).tvState(State.ON) == State.OFF;
        assert new Tv(screen(false, null), stick(reportOn), false).tvState(State.OFF) == State.ON;
        assert new Tv(screen(false, null), stick(""), false).tvState(State.OFF) == State.OFF;
        assert new Tv(screen(false, null), stick(standby), false).tvState(State.UNREACHABLE) == State.UNREACHABLE;
        Tv on = new Tv(screen(false, null), stick(reportOn), false);
        on.togglePower(State.ON);
        expect("tv off", "stick 223");

        // TV alone: input to the TV, no switch, its own apps; FireStick-only things are refused.
        Tv alone = new Tv(screen(false, true), null, false);
        assert !alone.showSwitch() && alone.can("apps") && !alone.can("films");
        alone.key(23);
        alone.launch("netflix");
        expect("tv key 23", "tv launch netflix");
        assert alone.apps().get(0)[1].equals("Netflix");
        assert alone.state() == State.ON;
        assert new Tv(screen(false, null), null, false).state() == State.UNREACHABLE;
        boolean refused = false;
        try {
            alone.claudeFm();
        } catch (UnsupportedOperationException e) {
            refused = true;
        }
        assert refused;

        // FireStick alone: no switch, no TV volume level, volume keys to the stick.
        Tv stickOnly = new Tv(null, stick(""), false);
        assert !stickOnly.showSwitch() && stickOnly.can("films") && !stickOnly.can("volume");
        stickOnly.volumeKey(24);
        expect("stick 24");
        try {
            stickOnly.tvVolume(20);
            throw new AssertionError("volume without a paired TV");
        } catch (IOException e) {
            assert e.getMessage().contains("Pair") : e.getMessage();
        }

        // Nothing saved: everything says so, nothing crashes.
        Tv none = new Tv(null, null, false);
        assert none.state() == State.UNREACHABLE && !none.hasScreen() && !none.hasStick();
        try {
            none.key(19);
            throw new AssertionError("key with no TV");
        } catch (IOException e) {
            assert e.getMessage().contains("Add a TV") : e.getMessage();
        }
    }
}
