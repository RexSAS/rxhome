package com.andrea.rxhome;

import java.util.List;

public class SpotifyTest {
    public static void main(String[] a) throws Exception {
        // PKCE: the RFC 7636 example values.
        assert Spotify.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").equals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        String v = Spotify.verifier();
        assert v.length() >= 43 && v.length() <= 128 && v.matches("[A-Za-z0-9_-]+") : v;
        assert !v.equals(Spotify.verifier());

        // The login page address.
        String url = Spotify.authorizeUrl("cid", "http://127.0.0.1:43821/callback", "CH", "ST");
        assert url.startsWith("https://accounts.spotify.com/authorize?") : url;
        for (String part : new String[] {"client_id=cid", "response_type=code", "redirect_uri=http%3A%2F%2F127.0.0.1%3A43821%2Fcallback",
                "code_challenge_method=S256", "code_challenge=CH", "state=ST",
                "scope=user-read-playback-state+user-modify-playback-state"}) {
            assert url.contains(part) : part + " missing in " + url;
        }

        // What the browser sends back to the phone's 127.0.0.1 listener.
        assert "abc".equals(Spotify.callbackCode("GET /callback?code=abc&state=ST HTTP/1.1", "ST"));
        assert "abc".equals(Spotify.callbackCode("GET /callback?state=ST&code=abc HTTP/1.1", "ST"));
        assert Spotify.callbackCode("GET /callback?code=abc&state=EVIL HTTP/1.1", "ST") == null;     // not our login
        assert Spotify.callbackCode("GET /callback?error=access_denied&state=ST HTTP/1.1", "ST") == null;
        assert Spotify.callbackCode("GET /favicon.ico HTTP/1.1", "ST") == null;

        // Tokens: a refresh may leave out the refresh token (keep the old one).
        Spotify.Tokens t = Spotify.parseTokens("{\"access_token\":\"A\",\"refresh_token\":\"R\",\"expires_in\":3600}", "old");
        assert t.access.equals("A") && t.refresh.equals("R") && t.expiresIn == 3600;
        assert Spotify.parseTokens("{\"access_token\":\"B\",\"expires_in\":3600}", "old").refresh.equals("old");

        // Speakers: restricted ones can't be controlled, so they're left out.
        List<Spotify.Device> ds = Spotify.parseDevices("{\"devices\":["
            + "{\"id\":\"d1\",\"name\":\"Cucina\",\"type\":\"Speaker\",\"is_active\":true,\"is_restricted\":false},"
            + "{\"id\":\"d2\",\"name\":\"Echo Dot\",\"type\":\"Speaker\",\"is_active\":false,\"is_restricted\":false},"
            + "{\"id\":\"d3\",\"name\":\"Locked\",\"type\":\"Speaker\",\"is_active\":false,\"is_restricted\":true},"
            + "{\"id\":null,\"name\":\"No id\",\"type\":\"Speaker\"}]}");
        assert ds.size() == 2 && ds.get(0).name.equals("Cucina") && ds.get(0).active && !ds.get(1).active : ds.size();
        assert Spotify.find(ds, "cucina").id.equals("d1") && Spotify.find(ds, " Echo Dot ").id.equals("d2");
        assert Spotify.find(ds, "Bagno") == null;

        // Now playing: a song, an episode, nothing.
        Spotify.Playing p = Spotify.parsePlaying("{\"is_playing\":true,\"device\":{\"name\":\"Cucina\"},"
            + "\"item\":{\"name\":\"Song\",\"artists\":[{\"name\":\"A\"},{\"name\":\"B\"}]}}");
        assert p.title.equals("Song") && p.by.equals("A, B") && p.device.equals("Cucina") && p.playing;
        Spotify.Playing ep = Spotify.parsePlaying("{\"is_playing\":false,\"device\":{\"name\":\"Bagno\"},"
            + "\"item\":{\"name\":\"Ep 1\",\"show\":{\"name\":\"Pod\"}}}");
        assert ep.title.equals("Ep 1") && ep.by.equals("Pod") && !ep.playing;
        assert Spotify.parsePlaying("") == null && Spotify.parsePlaying("{\"is_playing\":false,\"item\":null}") == null;
        assert Spotify.transferBody("d1").equals("{\"device_ids\":[\"d1\"],\"play\":true}");

        // Controls: the playing speaker's volume (-1 when Spotify can't set it there), and the commands.
        Spotify.Playing vol = Spotify.parsePlaying("{\"is_playing\":true,\"device\":{\"name\":\"Echo\",\"volume_percent\":35,"
            + "\"supports_volume\":true},\"item\":{\"name\":\"S\",\"artists\":[]}}");
        assert vol.volume == 35 : vol.volume;
        assert Spotify.parsePlaying("{\"is_playing\":true,\"device\":{\"name\":\"Tv\",\"volume_percent\":50,\"supports_volume\":false},"
            + "\"item\":{\"name\":\"S\"}}").volume == -1;
        assert Spotify.parsePlaying("{\"is_playing\":true,\"device\":{\"name\":\"Tv\",\"volume_percent\":null},\"item\":{\"name\":\"S\"}}").volume == -1;
        assert Spotify.volumePath(35).equals("/me/player/volume?volume_percent=35");
        assert Spotify.volumePath(150).equals("/me/player/volume?volume_percent=100") && Spotify.volumePath(-5).endsWith("=0");

        // This phone in Spotify's list: by its name, else the only smartphone; never someone else's.
        List<Spotify.Device> withPhones = Spotify.parseDevices("{\"devices\":["
            + "{\"id\":\"e\",\"name\":\"Echo\",\"type\":\"Speaker\"},"
            + "{\"id\":\"p\",\"name\":\"Nothing Phone (4a) Pro\",\"type\":\"Smartphone\"},"
            + "{\"id\":\"q\",\"name\":\"Other phone\",\"type\":\"Smartphone\"}]}");
        assert Spotify.phone(withPhones, "nothing phone (4a) pro").id.equals("p");
        assert Spotify.phone(withPhones, "A069P") == null;                         // two phones, neither named: unsure
        assert Spotify.phone(withPhones.subList(0, 2), "A069P").id.equals("p");   // the only one
        assert Spotify.phone(withPhones.subList(0, 1), "A069P") == null;

        // Your own names for speakers ("Spotify name=Your name" lines); unnamed ones keep Spotify's.
        String names = Spotify.rename("", "Echo Dot", "Salotto");
        assert names.equals("Echo Dot=Salotto") : names;
        names = Spotify.rename(names, "Philips TV", "Tv");
        assert Spotify.label(names, "Echo Dot").equals("Salotto") && Spotify.label(names, "Philips TV").equals("Tv");
        assert Spotify.label(names, "Cucina").equals("Cucina");
        names = Spotify.rename(names, "Echo Dot", " ");                 // empty: back to Spotify's name
        assert Spotify.label(names, "Echo Dot").equals("Echo Dot") && names.equals("Philips TV=Tv") : names;
        names = Spotify.rename(names, "Philips TV", "Tv: big=screen");  // separators can't break the format
        assert Spotify.label(names, "Philips TV").equals("Tv big screen") : Spotify.label(names, "Philips TV");
    }
}
