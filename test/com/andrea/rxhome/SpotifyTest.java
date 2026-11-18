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
