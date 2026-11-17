package com.andrea.rxhome;

import java.util.Map;

public class PhilipsTest {
    // Reference values from python3 hmac/hashlib, following pylips (JointSPACE v6 pairing) and RFC 2617 digest.
    public static void main(String[] a) throws Exception {
        assert Philips.signature(1700000000L, "4321").equals("ZWUxMDA3MTYyMTAzMDIwYjFjMzhmODBmODhlNGY1ZWI3NjdkODM0Zg==")
            : Philips.signature(1700000000L, "4321");

        Map<String, String> ch = Philips.challenge("Digest realm=\"XTV\", qop=\"auth,auth-int\", nonce=\"abc123\", opaque=\"op\"");
        assert ch.get("realm").equals("XTV") && ch.get("nonce").equals("abc123") && ch.get("qop").equals("auth,auth-int");
        assert ch.get("opaque").equals("op");

        String h = Philips.digest("dev123", "authkey", "POST", "/6/input/key", ch, "0a4f113b", 1);
        assert h.contains("response=\"49c21ed3a3e3851b91100ab5e639c7de\"") : h;
        assert h.contains("qop=auth") && h.contains("nc=00000001") && h.contains("cnonce=\"0a4f113b\"") && h.contains("opaque=\"op\"") : h;
        // Servers that don't send qop get the RFC 2069 form.
        String old = Philips.digest("dev123", "authkey", "POST", "/6/input/key", Philips.challenge("Digest realm=\"XTV\", nonce=\"abc123\""), "x", 1);
        assert old.contains("response=\"dbf4191d1d2b07b4ad55795a65365fcd\"") && !old.contains("qop") : old;

        // Wake-on-LAN: 6 × 0xFF, then the MAC 16 times (any separator, any case).
        byte[] wol = Philips.magicPacket("aa:BB-cc:dd:ee:FF");
        assert wol.length == 102 && wol[0] == (byte) 0xff && wol[5] == (byte) 0xff;
        assert wol[6] == (byte) 0xaa && wol[11] == (byte) 0xff && wol[96] == (byte) 0xaa && wol[101] == (byte) 0xff;
        try {
            Philips.magicPacket("nonsense");
            throw new AssertionError("bad MAC accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // The TV's MAC from GET /6/network/devices: the interface with the address we talk to.
        String net = "[{\"type\":\"Ethernet\",\"mac\":\"11:22:33:44:55:66\",\"ip\":\"\"},"
            + "{\"type\":\"Wifi\",\"mac\":\"aa:bb:cc:dd:ee:ff\",\"ip\":\"192.168.1.221\"}]";
        assert Philips.macFor(net, "192.168.1.221").equals("aa:bb:cc:dd:ee:ff") : Philips.macFor(net, "192.168.1.221");
        assert Philips.macFor("[]", "192.168.1.221") == null;

        // Live, read-only (RXHOME_PHILIPS=192.168.1.221): the TV answers and wants digest pairing.
        String ip = System.getenv("RXHOME_PHILIPS");
        if (ip != null) assert Philips.needsPairing(ip) : "expected digest_auth_pairing";
    }
}
