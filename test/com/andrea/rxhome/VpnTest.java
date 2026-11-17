package com.andrea.rxhome;

public class VpnTest {
    public static void main(String[] a) {
        long now = 1_000_000;
        // Away from home: neither the server nor the FireStick answers → bring the tunnel up.
        assert Vpn.shouldStart(State.UNREACHABLE, State.UNREACHABLE, now, 0);
        // At home with the server down: the stick answers, a tunnel wouldn't help.
        assert !Vpn.shouldStart(State.UNREACHABLE, State.ON, now, 0);
        assert !Vpn.shouldStart(State.UNREACHABLE, State.OFF, now, 0);
        // Server reachable (home, or the tunnel is already up): nothing to do.
        assert !Vpn.shouldStart(State.ON, State.UNREACHABLE, now, 0);
        // At most once a minute, so a tunnel that can't connect isn't hammered.
        assert !Vpn.shouldStart(State.UNREACHABLE, State.UNREACHABLE, now, now - 30_000);
        assert Vpn.shouldStart(State.UNREACHABLE, State.UNREACHABLE, now, now - 61_000);
        assert Vpn.TUNNEL.equals("rxfilms");
    }
}
