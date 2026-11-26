package com.andrea.rxhome;

public class VpnTest {
    public static void main(String[] a) {
        long now = 1_000_000;
        // Away = nothing at home answers (FireStick, TV, lights). At home with the server off: no tunnel.
        assert Vpn.shouldStart(false, false, now, 0);
        assert !Vpn.shouldStart(true, false, now, 0);   // home devices answer: we're home
        assert !Vpn.shouldStart(false, true, now, 0);   // the server answers (home, or tunnel already up)
        // At most once a minute, so a tunnel that can't connect isn't hammered.
        assert !Vpn.shouldStart(false, false, now, now - 30_000);
        assert Vpn.shouldStart(false, false, now, now - 61_000);
        assert Vpn.TUNNEL.equals("rxfilms");
        // The server is the WireGuard end: VPN up 5 s with home still silent = server off, tunnel dead.
        assert Vpn.dead(now - 5_000, now);
        assert !Vpn.dead(now - 3_000, now); // still handshaking
        assert !Vpn.dead(0, now);           // the VPN isn't up yet (WireGuard starting)

        // Cloudflare's whitelist: through (even to a down server, 52x) = no tunnel needed; 403 or nothing = tunnel.
        assert Server.passes(200) && Server.passes(521) && Server.passes(522) && Server.passes(502);
        assert !Server.passes(403) && !Server.passes(-1);

        // Auto-off: the tunnel stays while rxhome or Jellyfin is in front (screen on).
        assert Vpn.ours("com.andrea.rxhome") && Vpn.ours("org.jellyfin.mobile");
        assert !Vpn.ours("com.whatsapp") && !Vpn.ours(null);
        // ...and goes after 10 s without either, so a quick hop to another app doesn't drop it.
        assert !Vpn.shouldStop(now - 5_000, now);
        assert !Vpn.shouldStop(now - 9_000, now);
        assert Vpn.shouldStop(now - 11_000, now);
        assert Vpn.JELLYFIN.equals("org.jellyfin.mobile");

        // After a Wi-Fi change (rxhome's tunnel dropped first): up again only if nothing at home answers,
        // you're in rxhome or Jellyfin, and no VPN is up already.
        assert Vpn.wantUp(false, true, false);
        assert !Vpn.wantUp(true, true, false);   // home answers: home Wi-Fi
        assert !Vpn.wantUp(false, false, false); // not in rxhome or Jellyfin
        assert !Vpn.wantUp(false, true, true);   // a VPN is already up
    }
}
