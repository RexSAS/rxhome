package com.andrea.rxhome;

public class TapoTest {
    // Reference values from python3 + hashlib + cryptography, following python-kasa's KLAP v2:
    // local = 00..0f, remote = 10..1f, email a@b.c, password pw, first request {"method":"get_device_info"}.
    public static void main(String[] a) throws Exception {
        byte[] local = new byte[16], remote = new byte[16];
        for (int i = 0; i < 16; i++) { local[i] = (byte) i; remote[i] = (byte) (16 + i); }

        byte[] auth = Tapo.authHash("a@b.c", "pw");
        assert hex(auth).equals("37b46eed1db4ab9849caa8bf8b397258c4130d9672ce3c977347ee792f3444e4");
        assert hex(Tapo.sha256(local, remote, auth)).equals("0a4df19e559d8832a789dc82bee5c0afaea35c00b760e33fbd4fb74fd554fd79");
        assert hex(Tapo.sha256(remote, local, auth)).equals("a3d27894f875541d3cde09a6201adb915d56d14e61b85c630e40620b414654c1");

        Tapo.Session s = new Tapo.Session(local, remote, auth);
        byte[] enc = s.encrypt("{\"method\":\"get_device_info\"}");
        assert s.seq == -2016276748 : s.seq;
        assert hex(enc).equals("7de49a8d0415551d6ea5a11512c67ccc980d0892d103e8941c49ba2724b450c6"
            + "5c727ee8f8ffe91667af202c1557c5cb611b8b8ea5e90767a62f12722c311aa3");
        // The device answers with the same layout (32-byte signature, then ciphertext) under the same seq.
        assert s.decrypt(enc).equals("{\"method\":\"get_device_info\"}");
        // A garbled reply is an ordinary IOException (card goes grey), not a crash.
        try {
            s.decrypt(new byte[40]);
            throw new AssertionError("garbage decrypted");
        } catch (java.io.IOException expected) {
            // ok
        }

        // set_device_info only carries what was asked, and drops colour temperature on devices without it.
        assert Tapo.setParams(true, 20, 2700, false).equals("{\"device_on\":true,\"brightness\":20}");
        assert Tapo.setParams(false, null, null, true).equals("{\"device_on\":false}");
        assert Tapo.setParams(null, null, 2700, true).equals("{\"color_temp\":2700}");
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
