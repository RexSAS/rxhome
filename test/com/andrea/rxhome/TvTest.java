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

    static Sky sky() {
        return new Sky("x") {
            @Override void press(int code) { calls.add("sky " + code); }
            @Override boolean answers() { return true; }
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
        Tv both = new Tv(screen(false, null), stick(""), null, "");
        assert both.showSwitch() && both.mode().equals("stick") && both.modes().equals(Arrays.asList("tv", "stick"));
        both.key(19);
        both.keyDown(20);
        both.keyUp(20);
        expect("stick 19", "stick down 20", "stick up 20");
        both.setMode("tv");
        both.key(19);
        both.keyDown(20);
        both.keyRepeat(20, 1);
        both.keyUp(20);
        expect("tv key 19", "tv key 20", "tv key 20");
        // Volume always to the TV, apps always to the stick, whatever the switch says.
        both.volumeKey(24);
        both.volumeKey(178); // SOURCE: the TV's inputs
        both.launch("com.x");
        expect("tv key 24", "tv key 178", "stick launch com.x");
        both.setMode("stick");
        both.volumeKey(25);
        expect("tv key 25");

        // Power: the TV first, then the stick.
        both.sleep();
        expect("tv off", "stick 223");
        both.wake();
        expect("tv on", "stick 224");
        // powerOnWhenScreenUnreachable: the stick still wakes, no error.
        Tv deaf = new Tv(screen(true, null), stick(""), null, "");
        deaf.wake();
        expect("stick 224");
        deaf.sleep();
        expect("stick 223");

        // The TV's power: its own word if it has one, else HDMI-CEC through the stick, else the stick's state.
        assert new Tv(screen(false, true), stick(standby), null, "").tvState(State.ON) == State.ON;
        assert new Tv(screen(false, null), stick(standby), null, "").tvState(State.ON) == State.OFF;
        assert new Tv(screen(false, null), stick(reportOn), null, "").tvState(State.OFF) == State.ON;
        assert new Tv(screen(false, null), stick(""), null, "").tvState(State.OFF) == State.OFF;
        assert new Tv(screen(false, null), stick(standby), null, "").tvState(State.UNREACHABLE) == State.UNREACHABLE;
        Tv on = new Tv(screen(false, null), stick(reportOn), null, "");
        on.togglePower(State.ON);
        expect("tv off", "stick 223");

        // TV alone: input to the TV, no switch, its own apps; FireStick-only things are refused.
        Tv alone = new Tv(screen(false, true), null, null, "");
        assert !alone.showSwitch() && alone.can("apps") && !alone.can("films");
        alone.key(23);
        alone.launch("netflix");
        expect("tv key 23", "tv launch netflix");
        assert alone.apps().get(0)[1].equals("Netflix");
        assert alone.state() == State.ON;
        assert new Tv(screen(false, null), null, null, "").state() == State.UNREACHABLE;
        boolean refused = false;
        try {
            alone.claudeFm();
        } catch (UnsupportedOperationException e) {
            refused = true;
        }
        assert refused;

        // A TV alone that can't tell its power (Philips): the button alternates, starting with on, so it
        // can always be switched off too.
        Tv blind = new Tv(screen(false, null), null, null, "");
        blind.togglePower(blind.state());
        blind.togglePower(blind.state());
        blind.togglePower(blind.state());
        expect("tv on", "tv off", "tv on");

        // TV + FireStick + Sky: three remote styles, FireStick first by default; Sky gets the arrows when chosen,
        // volume still goes to the TV, apps to the FireStick.
        Tv all = new Tv(screen(false, true), stick(""), sky(), "");
        assert all.modes().equals(Arrays.asList("tv", "stick", "sky")) && all.mode().equals("stick") && all.showSwitch();
        all.setMode("sky");
        all.key(19);
        all.keyDown(23);
        all.keyRepeat(23, 1);
        all.keyUp(23);
        all.skyKey(Sky.CHANNEL_UP);
        all.volumeKey(24);
        all.launch("com.x");
        expect("sky 16", "sky 1", "sky 1", "sky 6", "tv key 24", "stick launch com.x");
        // A remembered style that's gone (Sky unbound): back to the default.
        assert new Tv(screen(false, true), stick(""), null, "sky").mode().equals("stick");
        // TV with only a Sky box: TV and Sky styles, Sky by default.
        assert new Tv(screen(false, true), null, sky(), "").mode().equals("sky");
        // Nothing but the TV: no switch.
        assert !new Tv(screen(false, true), null, null, "").showSwitch();
        // A Sky box alone: its power key, its own state.
        Tv skyOnly = new Tv(null, null, sky(), "");
        assert skyOnly.state() == State.ON && !skyOnly.showSwitch() && !skyOnly.can("apps");
        skyOnly.togglePower(skyOnly.state());
        expect("sky 0");

        // FireStick alone: no switch, no TV volume level, volume keys to the stick.
        Tv stickOnly = new Tv(null, stick(""), null, "");
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
        Tv none = new Tv(null, null, null, "");
        assert none.state() == State.UNREACHABLE && !none.hasScreen() && !none.hasStick();
        try {
            none.key(19);
            throw new AssertionError("key with no TV");
        } catch (IOException e) {
            assert e.getMessage().contains("Add a TV") : e.getMessage();
        }
    }
}
