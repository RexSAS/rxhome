package com.andrea.rxhome;

import java.io.IOException;
import java.util.List;

/**
 * A TV's own network remote (Samsung, LG, Philips). Keys are Android keycodes (Gestures.UP etc.); a key
 * or command the TV can't do throws UnsupportedOperationException, and can() says so up front.
 */
interface Screen {

    void key(int keycode) throws IOException;

    /** The TV's own volume scale. */
    void setVolume(int level) throws IOException;

    void mute() throws IOException;

    void powerOff() throws IOException;

    /** Through the API if it still listens, then Wake-on-LAN (errors on one path don't stop the other). */
    void powerOn() throws IOException;

    /** Null when this TV can't tell (or doesn't answer). Never throws. */
    Boolean isOn();

    /** Installed apps as {appId, name}. */
    List<String[]> apps() throws IOException;

    void launch(String appId) throws IOException;

    /** "volume" (set a level), "mute", "apps", "power". */
    boolean can(String what);

    /** Switches a TV on from standby (needs its "switch on with Wi-Fi/LAN" setting). */
    static void wakeOnLan(String mac) throws IOException {
        byte[] p = magicPacket(mac);
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setBroadcast(true);
            java.net.InetAddress all = java.net.InetAddress.getByName("255.255.255.255");
            for (int port : new int[] {9, 7}) s.send(new java.net.DatagramPacket(p, p.length, all, port));
        }
    }

    static byte[] magicPacket(String mac) {
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("Not a MAC address: " + mac);
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        byte[] p = new byte[102];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, p, 6 + 6 * i, 6);
        return p;
    }
}
