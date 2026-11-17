package com.andrea.rxhome;

/**
 * When to switch on the WireGuard tunnel home ("rxfilms"): only away from home, i.e. when neither the
 * server nor the FireStick answers (if the stick answers we're home and a tunnel wouldn't help), and at
 * most once a minute. Sending it is MainActivity's job (an Android broadcast to the WireGuard app).
 */
final class Vpn {

    static final String TUNNEL = "rxfilms";
    static final String ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP";
    static final String PACKAGE = "com.wireguard.android";
    static final String PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS";
    static final long RETRY_MS = 60_000;

    private Vpn() {}

    static boolean shouldStart(State server, State stick, long now, long lastTry) {
        return server == State.UNREACHABLE && stick == State.UNREACHABLE && now - lastTry >= RETRY_MS;
    }
}
