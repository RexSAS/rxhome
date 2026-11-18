package com.andrea.rxhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Getting the apps rxhome's Apps page uses onto a TV: RxFilms and SmartTube from their GitHub releases
 * (streamed into the TV's installer), Spotify and the official YouTube from the TV's own store.
 */
final class TvInstall {

    static final String RXFILMS_RELEASES = "https://api.github.com/repos/RexSAS/RxFilms-Tv-AppInstaller/releases/latest",
        SMARTTUBE_RELEASES = "https://api.github.com/repos/yuliskov/SmartTube/releases/latest";
    static final String RXFILMS_APK = "rxfilms-tv-", SMARTTUBE_APK = "SmartTube_stable";
    /** The official YouTube apps: Fire TV's, then Android TV's. */
    static final String[] YOUTUBE = {"com.amazon.firetv.youtube", "com.google.android.youtube.tv"};

    private TvInstall() {}

    /** From a GitHub release: the APK whose name starts with prefix, built for this processor (abi), else "universal". */
    static String pickApk(String releaseJson, String prefix, String abi) {
        try {
            JSONArray assets = new JSONObject(releaseJson).optJSONArray("assets");
            String any = null, universal = null;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name"), url = a.optString("browser_download_url");
                if (!name.startsWith(prefix) || !name.endsWith(".apk") || url.isEmpty()) continue;
                if (name.contains(abi)) return url;
                if (name.contains("universal")) universal = url;
                else if (!name.matches(".*(arm64|armeabi|x86|mips).*")) any = url; // one APK for all
            }
            return universal != null ? universal : any;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Opens an app's page in the TV's store (amazon = a Fire TV). */
    static String storeCommand(String pkg, boolean amazon) {
        String uri = amazon ? "amzn://apps/android?p=" + pkg : "market://details?id=" + pkg;
        return "am start -a android.intent.action.VIEW -d '" + uri + "'";
    }

    /** The official YouTube app among these packages, or null. */
    static String youtube(String[] packages) {
        for (String y : YOUTUBE) for (String p : packages) if (p.equals(y)) return y;
        return null;
    }

    /** The TV's installer answered OK. */
    static boolean installed(String reply) {
        return reply.trim().startsWith("Success");
    }
}
