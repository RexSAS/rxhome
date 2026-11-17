package com.andrea.rxhome;

public class ConfirmTest {
    public static void main(String[] a) {
        // A jittery double-tap (second tap 150 ms later) must not press the server's button.
        Confirm c = new Confirm();
        assert !c.tap(1000);
        assert !c.tap(1150);
        // A deliberate second tap a moment later does.
        assert c.tap(2000);
        // After pressing, it starts over: the next tap only arms again.
        assert !c.tap(2500);
        // Too late (over 3 s): that tap re-arms instead of pressing.
        assert !c.tap(6000);
        assert c.tap(7000);
    }
}
