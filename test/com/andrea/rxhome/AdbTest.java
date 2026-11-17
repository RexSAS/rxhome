package com.andrea.rxhome;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

public class AdbTest {
    public static void main(String[] a) throws Exception {
        File priv = new File(System.getProperty("user.home"), ".android/adbkey");
        File pub = new File(System.getProperty("user.home"), ".android/adbkey.pub");
        KeyPair desktop = null;
        if (priv.exists() && pub.exists()) {
            desktop = pem(priv);
            String expected = new String(Files.readAllBytes(pub.toPath()), "UTF-8").split(" ")[0].trim();
            String got = Adb.publicKeyString((RSAPublicKey) desktop.getPublic(), "x").split(" ")[0];
            assert got.equals(expected) : "pubkey format differs";
        }

        // Saved keys round-trip.
        KeyPair k = Adb.generateKey();
        KeyPair back = Adb.load(k.getPrivate().getEncoded(), k.getPublic().getEncoded());
        assert back.getPublic().equals(k.getPublic());

        String host = System.getenv("RXHOME_ADB");
        if (host == null || desktop == null) return;

        Adb adb = new Adb(host, 5555, desktop);
        String model = adb.shell("getprop ro.product.model", 5000).trim();
        assert model.equals("AFTMA08C15") : model;
        assert adb.shell("echo hi", 5000).trim().equals("hi"); // connection reused
        adb.close();

        // Pops a real "Allow USB debugging?" on the TV; a prompt cut off mid-way can leave adbd stuck
        // ("prompt currently pending") until ADB debugging is toggled. So it only runs when asked for.
        if (System.getenv("RXHOME_ADB_PROMPT") == null) return;
        // A key the TV has never seen: we say a prompt is up, keep the line open for the user to accept
        // (closing it makes the TV drop the prompt), then give a clear error instead of hanging forever.
        Adb fresh = new Adb(host, 5555, Adb.generateKey());
        fresh.authWaitMs = 4000;
        boolean[] prompted = {false};
        fresh.onKeyPrompt = () -> prompted[0] = true;
        long t0 = System.currentTimeMillis();
        try {
            fresh.shell("echo x", 1500);
            throw new AssertionError("unknown key was accepted");
        } catch (Adb.AdbAuthException expected) {
            long took = System.currentTimeMillis() - t0;
            assert prompted[0] : "no prompt callback";
            assert took >= 3500 && took < 8000 : "waited " + took + " ms";
        }
    }

    static KeyPair pem(File f) throws Exception {
        String b64 = new String(Files.readAllBytes(f.toPath()), "UTF-8").replaceAll("-----[^-]+-----|\\s", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey p = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        return new KeyPair(kf.generatePublic(new RSAPublicKeySpec(p.getModulus(), p.getPublicExponent())), p);
    }

}
