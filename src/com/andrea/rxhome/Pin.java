package com.andrea.rxhome;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TVs use self-signed certificates: the one seen at pairing is pinned (SHA-256, lowercase hex) and only
 * that one is accepted after. Names aren't checked: identity is the pin.
 */
final class Pin {

    private Pin() {}

    /** Accepts only the certificate with this pin; pin null = pairing: accept any and record it in seen[0]. */
    static SSLSocketFactory factory(String pin, String[] seen) throws IOException {
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("not a server");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                String got = sha256(chain[0]);
                if (pin == null && seen != null) seen[0] = got;
                else if (!got.equals(pin)) throw new CertificateException("Not the paired TV: pair it again in Settings");
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, null);
            return ctx.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The pin of the certificate host:port shows now (for pairing). */
    static String capture(String host, int port) throws IOException {
        String[] seen = new String[1];
        try (SSLSocket s = (SSLSocket) factory(null, seen).createSocket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 3000);
            s.setSoTimeout(5000);
            s.startHandshake();
        }
        if (seen[0] == null) throw new IOException("The TV showed no certificate");
        return seen[0];
    }

    private static String sha256(X509Certificate c) throws CertificateException {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(c.getEncoded())) s.append(String.format("%02x", x));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }
}
