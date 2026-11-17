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
    static final String ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN";
    static final String JELLYFIN = "org.jellyfin.mobile";
    static final long GRACE_MS = 10_000;

    private Vpn() {}

    /** The apps the tunnel is for: while one of them is in front, it stays up. */
    static boolean ours(String pkg) {
        return "com.andrea.rxhome".equals(pkg) || JELLYFIN.equals(pkg);
    }

    /** Off after GRACE_MS without rxhome or Jellyfin in front, so a quick hop elsewhere doesn't drop it. */
    static boolean shouldStop(long lastSeenOurs, long now) {
        return now - lastSeenOurs > GRACE_MS;
    }

    static boolean shouldStart(State server, State stick, long now, long lastTry) {
        return server == State.UNREACHABLE && stick == State.UNREACHABLE && now - lastTry >= RETRY_MS;
    }
}
