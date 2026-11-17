package com.andrea.rxhome;

public class JellyfinTest {
    public static void main(String[] a) throws Exception {
        // The first item of /UserItems/Resume or /Shows/NextUp, labelled like rxhome-tv's rows.
        String film = "{\"Items\":[{\"Id\":\"0f3a9b6c1d2e4f5a8b7c6d5e4f3a2b1c\",\"Type\":\"Movie\",\"Name\":\"Dune: Part Two\","
            + "\"ProductionYear\":2024},{\"Id\":\"ffff\",\"Name\":\"Second\"}],\"TotalRecordCount\":2}";
        Jellyfin.Item f = Jellyfin.first(film);
        assert f.id.equals("0f3a9b6c1d2e4f5a8b7c6d5e4f3a2b1c") && f.label().equals("Dune: Part Two") : f.label();
        String ep = "{\"Items\":[{\"Id\":\"aaaabbbbccccddddeeeeffff00001111\",\"Type\":\"Episode\",\"Name\":\"Pilot\","
            + "\"SeriesName\":\"Severance\",\"ParentIndexNumber\":2,\"IndexNumber\":5}]}";
        assert Jellyfin.first(ep).label().equals("Severance S2:E5") : Jellyfin.first(ep).label();
        assert Jellyfin.first("{\"Items\":[]}") == null;
        assert Jellyfin.first("not json") == null;

        // Quick Connect: the code to approve, then the login once approved.
        Jellyfin.Start s = Jellyfin.start("{\"Secret\":\"s3cr3t\",\"Code\":\"123456\",\"Authenticated\":false}");
        assert s.secret.equals("s3cr3t") && s.code.equals("123456");
        Jellyfin.Login l = Jellyfin.login("{\"AccessToken\":\"tok\",\"User\":{\"Id\":\"u1\",\"Name\":\"andrea\"}}");
        assert l.token.equals("tok") && l.userId.equals("u1");

        // TV commands: exact, and nothing that could break out of the shell gets through.
        assert FireTv.viewCommand("spotify:collection:tracks", "com.spotify.tv.android")
            .equals("am start -a android.intent.action.VIEW -d 'spotify:collection:tracks' -p com.spotify.tv.android");
        assert FireTv.itemCommand("org.rxfilms.tv/org.jellyfin.androidtv.ui.startup.StartupActivity", "0f3a9b6c1d2e4f5a8b7c6d5e4f3a2b1c")
            .equals("am start -n org.rxfilms.tv/org.jellyfin.androidtv.ui.startup.StartupActivity --es ItemId 0f3a9b6c1d2e4f5a8b7c6d5e4f3a2b1c");
        for (String bad : new String[] {"x; reboot", "spotify:a b", "it's", "$(id)", "a|b", "a`b`"}) {
            try {
                FireTv.viewCommand(bad, "com.spotify.tv.android");
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            FireTv.itemCommand("org.rxfilms.tv/x", "0f3a; reboot");
            throw new AssertionError("accepted a bad item id");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
