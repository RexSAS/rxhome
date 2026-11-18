package com.andrea.rxhome;

public class TvInstallTest {
    public static void main(String[] a) {
        String smart = "{\"tag_name\":\"32.71s\",\"assets\":["
            + "{\"name\":\"SmartTube_stable_32.71_arm64-v8a.apk\",\"browser_download_url\":\"https://x/arm64.apk\"},"
            + "{\"name\":\"SmartTube_stable_32.71_armeabi-v7a.apk\",\"browser_download_url\":\"https://x/v7a.apk\"},"
            + "{\"name\":\"SmartTube_stable_32.71_universal.apk\",\"browser_download_url\":\"https://x/universal.apk\"}]}";
        // The APK built for the stick's processor; else the universal one.
        assert TvInstall.pickApk(smart, "SmartTube_stable", "armeabi-v7a").equals("https://x/v7a.apk");
        assert TvInstall.pickApk(smart, "SmartTube_stable", "arm64-v8a").equals("https://x/arm64.apk");
        assert TvInstall.pickApk(smart, "SmartTube_stable", "mips").equals("https://x/universal.apk");
        // RxFilms: its TV app, not the phone installer next to it.
        String rx = "{\"assets\":[{\"name\":\"rxfilms-installer-v1.3.2.apk\",\"browser_download_url\":\"https://y/installer.apk\"},"
            + "{\"name\":\"rxfilms-tv-v1.3.2.apk\",\"browser_download_url\":\"https://y/tv.apk\"}]}";
        assert TvInstall.pickApk(rx, "rxfilms-tv-", "arm64-v8a").equals("https://y/tv.apk");
        assert TvInstall.pickApk("{\"assets\":[]}", "rxfilms-tv-", "x") == null;
        assert TvInstall.pickApk("not json", "rxfilms-tv-", "x") == null;

        // Store apps open on the TV's own store page (Amazon on a FireStick, Google Play elsewhere).
        assert TvInstall.storeCommand("com.spotify.tv.android", true)
            .equals("am start -a android.intent.action.VIEW -d 'amzn://apps/android?p=com.spotify.tv.android'");
        assert TvInstall.storeCommand("com.google.android.youtube.tv", false)
            .equals("am start -a android.intent.action.VIEW -d 'market://details?id=com.google.android.youtube.tv'");

        // The official YouTube app on this TV, if any.
        assert TvInstall.youtube(new String[] {"com.amazon.firetv.youtube", "x"}).equals("com.amazon.firetv.youtube");
        assert TvInstall.youtube(new String[] {"com.google.android.youtube.tv"}).equals("com.google.android.youtube.tv");
        assert TvInstall.youtube(new String[] {"org.smarttube.stable"}) == null;
        // A TV set to the official YouTube opens it where SmartTube would open; other apps unchanged.
        assert FireTv.appFor(FireTv.SMARTTUBE, "com.amazon.firetv.youtube").equals("com.amazon.firetv.youtube");
        assert FireTv.appFor(FireTv.SMARTTUBE, null).equals(FireTv.SMARTTUBE);
        assert FireTv.appFor(FireTv.SPOTIFY, "com.amazon.firetv.youtube").equals(FireTv.SPOTIFY);
        // The installer's answer.
        assert TvInstall.installed("Success\n") && !TvInstall.installed("Failure [INSTALL_FAILED_NO_MATCHING_ABIS]");
    }
}
