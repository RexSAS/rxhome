package com.andrea.rxhome;

/**
 * When to switch on the WireGuard tunnel home ("rxfilms"): only away from home, i.e. when nothing at
 * home answers (FireStick, TV, lights; the server may just be off), and at most once a minute. Sending it is MainActivity's job (an Android broadcast to the WireGuard app).
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
    static final long HANDSHAKE_MS = 5_000;
    static final long DEAD_RETRY_MS = 5 * 60_000;

    private Vpn() {}

    /** The apps the tunnel is for: while one of them is in front, it stays up. */
    static boolean ours(String pkg) {
        return "com.andrea.rxhome".equals(pkg) || JELLYFIN.equals(pkg);
    }

    /** After a Wi-Fi change: bring the tunnel up only if home is out of reach while you're in our apps. */
    static boolean wantUp(boolean homeAnswers, boolean oursInFront, boolean vpnUp) {
        return !homeAnswers && oursInFront && !vpnUp;
    }

    /** Off after GRACE_MS without rxhome or Jellyfin in front, so a quick hop elsewhere doesn't drop it. */
    static boolean shouldStop(long lastSeenOurs, long now) {
        return now - lastSeenOurs > GRACE_MS;
    }

    static boolean shouldStart(boolean homeSeen, boolean serverOn, long now, long lastTry) {
        return !homeSeen && !serverOn && now - lastTry >= RETRY_MS;
    }

    /**
     * The tunnel ends on the server: a live one answers WireGuard's first handshake within a second, and
     * WireGuard retries every 5 s. Up that long with home still silent = the server is off.
     */
    static boolean dead(long upAt, long now) {
        return upAt > 0 && now - upAt >= HANDSHAKE_MS;
    }
}
