package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Minimal ADB-over-TCP client: connect, authenticate with an RSA key, run shell commands. */
class Adb {

    static final class AdbAuthException extends IOException {
        AdbAuthException(String m) { super(m); }
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
        OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    // PKCS#1 DigestInfo header for SHA-1: adbd treats the 20-byte token as a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = hex("3021300906052b0e03021a05000414");

    private final String host;
    private final int port;
    private final KeyPair key;
    /** How long to hold the line while the TV shows "Allow USB debugging?": closing it drops the prompt. */
    volatile int authWaitMs = 60000;
    /** Called (on the calling thread) when the TV starts showing the prompt. */
    volatile Runnable onKeyPrompt;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextId = 1;

    Adb(String host, int port, KeyPair key) {
        this.host = host;
        this.port = port;
        this.key = key;
    }

    /** Runs a shell command and returns its output. Reconnects once if the connection went stale. */
    synchronized String shell(String cmd, int timeoutMs) throws IOException {
        boolean reused = socket != null;
        try {
            return run(cmd, timeoutMs);
        } catch (IOException e) {
            close();
            // Only a reused connection can be stale; a fresh connect that failed won't do better twice.
            if (!reused || e instanceof AdbAuthException) throw e;
            return run(cmd, timeoutMs);
        }
    }

    synchronized void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        socket = null;
    }

    private String run(String cmd, int timeoutMs) throws IOException {
        if (socket == null) connect(timeoutMs);
        socket.setSoTimeout(timeoutMs);
        int local = nextId++;
        send(OPEN, local, 0, (("shell:" + cmd) + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue; // stray message for an older stream
            if (m.cmd == WRTE) {
                result.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.cmd == CLSE) {
                send(CLSE, local, m.arg0, new byte[0]);
                return result.toString("UTF-8");
            }
        }
    }

    private void connect(int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), Math.min(timeoutMs, 1500));
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        send(CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (java.net.SocketTimeoutException e) {
                if (signed) throw new AdbAuthException("Accept the USB debugging prompt on the TV");
                throw e;
            }
            if (m.cmd == CNXN) return;
            if (m.cmd != AUTH || m.arg0 != 1) throw new IOException("Unexpected ADB message");
            if (!signed) {
                send(AUTH, 2, 0, sign(m.data));
                signed = true;
            } else {
                // Our signature was refused: offer the public key; the TV shows a prompt.
                send(AUTH, 3, 0, (publicKeyString((RSAPublicKey) key.getPublic(), "rxhome") + "\0")
                    .getBytes(StandardCharsets.UTF_8));
                s.setSoTimeout(authWaitMs);
                Runnable r = onKeyPrompt;
                if (r != null) r.run();
            }
        }
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(key.getPrivate());
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("Bad ADB message length");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    static KeyPair generateKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair load(byte[] pkcs8, byte[] x509) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(x509)), kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8)));
    }

    /** The public key in Android's adbkey.pub format: base64 of the mincrypt RSAPublicKey struct, then a name. */
    static String publicKeyString(RSAPublicKey k, String name) {
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv).put(le(n)).put(le(rr)).putInt(k.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array()) + " " + name;
    }

    private static byte[] le(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[256];
        for (int i = 0; i < 256 && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
