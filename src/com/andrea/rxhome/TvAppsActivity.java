package com.andrea.rxhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's apps, one per row: tap the app to open it on the TV, or a card on its right to go straight
 * somewhere. RxFilms: every Continue watching / Next up item (lists hidden when empty); Spotify: Liked
 * Songs / DJ X; YouTube: Claude FM. Cards carry thumbnails.
 */
public class TvAppsActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService pictures = Executors.newFixedThreadPool(4);
    private Config cfg;
    private Tv tv;

    private interface TvAction {
        String run() throws IOException; // what to tell the user, e.g. "Opening Dune on the TV"
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        tv = cfg.tv();
        Tvs.Saved active = cfg.active();
        tvId = active == null ? "" : active.uid;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, "Apps", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "On the TV", 13, Ui.DIM, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 16));
        col.addView(sub);

        if (!tv.can("films")) { // a TV without a FireStick: its own apps (RxFilms, Spotify links, Claude FM need the stick)
            tvApps(col);
            ScrollView s = new ScrollView(this);
            s.addView(col);
            setContentView(s);
            return;
        }

        LinearLayout films = row(col, R.drawable.ic_app_rxfilms, "RxFilms", "org.rxfilms.tv");
        loadFilms(films);

        LinearLayout spotify = row(col, R.drawable.ic_app_spotify, "Spotify", FireTv.SPOTIFY);
        ImageView liked = card(spotify, "Liked Songs", "", "♥", () -> {
            tv.view(FireTv.LIKED, FireTv.SPOTIFY);
            return "Playing your Liked Songs on the TV";
        });
        liked.setImageDrawable(gradient(0xFF4A1DD6, 0xFF8FD3C3));
        ImageView dj = card(spotify, "DJ X", "", "DJ X", () -> {
            tv.view(FireTv.DJ_X, FireTv.SPOTIFY);
            return "Starting DJ X on the TV";
        });
        dj.setImageDrawable(gradient(0xFF101418, Ui.RED));

        LinearLayout youtube = row(col, R.drawable.ic_app_youtube, "YouTube", FireTv.SMARTTUBE);
        ImageView claude = card(youtube, "Claude FM", "Live", null, () -> {
            tv.claudeFm();
            return "Playing Claude FM on the TV";
        });
        pictures.execute(() -> show(claude, fetch("https://i.ytimg.com/vi/" + FireTv.claudeFmId() + "/hqdefault_live.jpg")));
        checkApps(films, spotify, youtube);

        Ui.cascade(col, 0); // cards rise in one after another
        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
    }

    /** The TV's installed apps, one button each. */
    private void tvApps(LinearLayout col) {
        TextView wait = Ui.text(this, "Asking the TV…", 13, Ui.DIM, false);
        col.addView(wait);
        io.execute(() -> {
            java.util.List<String[]> apps;
            String err = null;
            try {
                apps = tv.apps();
            } catch (IOException | RuntimeException e) {
                apps = java.util.Collections.emptyList();
                err = e.getMessage();
            }
            java.util.List<String[]> list = apps;
            String why = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                col.removeView(wait);
                if (list.isEmpty()) col.addView(Ui.text(this, why != null ? why : "No apps listed by the TV", 13, Ui.DIM, false));
                for (String[] app : list) {
                    TextView b = Ui.button(this, app[1], false);
                    b.setOnClickListener(v -> {
                        Ui.haptic(v);
                        run(() -> { tv.launch(app[0]); return "Opening " + app[1] + " on the TV"; });
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = Ui.dp(this, 8);
                    col.addView(b, lp);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        pictures.shutdownNow();
        super.onDestroy();
    }

    /** One app: logo and name on the left (opens it), its cards stacked on the right. */
    private LinearLayout row(LinearLayout col, int logo, String name, String pkg) {
        LinearLayout tile = Ui.tile(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setGravity(Gravity.CENTER_HORIZONTAL);
        app.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(logo);
        app.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        TextView label = Ui.text(this, name, 17, Ui.TEXT, true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Ui.dp(this, 8), 0, 0);
        app.addView(label);
        app.setClickable(true);
        app.setFocusable(true);
        Ui.press(app);
        app.setContentDescription("Open " + name + " on the TV");
        app.setOnClickListener(v -> {
            Ui.haptic(v);
            run(() -> { tv.launch(pkg); return "Opening " + name + " on the TV"; });
        });
        tile.addView(app, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        tile.addView(cards, new LinearLayout.LayoutParams(0, -2, 1.7f));
        col.addView(tile);
        return cards;
    }

    /** A shortcut card: thumbnail (optionally with a big label on it), title, second line. Returns the thumbnail. */
    private ImageView card(LinearLayout cards, String title, String sub, String overlay, TvAction action) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.button(this, "", false).getBackground());

        FrameLayout thumbBox = new FrameLayout(this);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Ui.TILE);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        if (overlay != null) {
            TextView big = Ui.text(this, overlay, 26, 0xFFFFFFFF, true);
            big.setGravity(Gravity.CENTER);
            thumbBox.addView(big, new FrameLayout.LayoutParams(-1, -1));
        }
        c.addView(thumbBox, new LinearLayout.LayoutParams(-1, Ui.dp(this, 104)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 10);
        text.setPadding(p, p, p, p);
        TextView t = Ui.text(this, title, 14, Ui.TEXT, true);
        t.setMaxLines(2);
        text.addView(t);
        if (!sub.isEmpty()) text.addView(Ui.text(this, sub, 12, Ui.DIM, false));
        c.addView(text);

        c.setClickable(true);
        c.setFocusable(true);
        Ui.press(c);
        c.setContentDescription(title + (sub.isEmpty() ? "" : ", " + sub));
        c.setOnClickListener(v -> {
            Ui.haptic(v);
            run(action);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 10);
        cards.addView(c, lp);
        return thumb;
    }

    /** RxFilms: a card per Continue watching and Next up item; empty lists aren't shown. */
    private void loadFilms(LinearLayout cards) {
        Jellyfin jf = cfg.jellyfin();
        TextView note = Ui.text(this, jf == null ? "Sign in to Jellyfin in Settings for Continue watching and Next up" : "Loading…",
            13, Ui.DIM, false);
        cards.addView(note);
        if (jf == null) return;
        io.execute(() -> {
            List<Jellyfin.Item> resume, next;
            String error = null;
            try {
                resume = jf.resumeAll();
                next = jf.nextUpAll();
            } catch (IOException e) {
                resume = next = java.util.Collections.emptyList();
                error = e.getMessage();
            }
            List<Jellyfin.Item> r = resume, n = next;
            String err = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (err != null) {
                    note.setText(err);
                    return;
                }
                cards.removeView(note);
                section(cards, "Continue watching", r, jf);
                section(cards, "Next up", n, jf);
                if (r.isEmpty() && n.isEmpty()) cards.addView(Ui.text(this, "Nothing to watch right now", 13, Ui.DIM, false));
            });
        });
    }

    private void section(LinearLayout cards, String name, List<Jellyfin.Item> items, Jellyfin jf) {
        if (items.isEmpty()) return;
        TextView h = Ui.text(this, name.toUpperCase(), 12, Ui.RED, true);
        h.setLetterSpacing(0.08f);
        h.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        cards.addView(h);
        for (Jellyfin.Item it : items) {
            ImageView thumb = card(cards, it.title, it.episode, null, () -> {
                tv.openItem(FireTv.RXFILMS, it.id);
                return "Opening " + it.label() + " on the TV";
            });
            pictures.execute(() -> {
                try {
                    show(thumb, jf.image(it.image));
                } catch (IOException e) {
                    // No picture: the card keeps its plain background.
                }
            });
        }
    }

    private String tvId;

    private interface Tap {
        void on(TextView button);
    }

    /** What's missing on the TV gets a button in its row; YouTube: which app this TV plays it with. */
    private void checkApps(LinearLayout films, LinearLayout spotify, LinearLayout youtube) {
        io.execute(() -> {
            String[] pkgs;
            try {
                pkgs = tv.packages();
            } catch (IOException e) {
                return; // the TV isn't reachable: nothing to offer
            }
            java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(pkgs));
            String yt = TvInstall.youtube(pkgs);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (!have.contains("org.rxfilms.tv")) {
                    installButton(films, "INSTALL RXFILMS ON THE TV",
                        b -> download(b, TvInstall.RXFILMS_RELEASES, TvInstall.RXFILMS_APK, "RxFilms", () -> remove(b)));
                }
                if (!have.contains(FireTv.SPOTIFY)) installButton(spotify, "GET SPOTIFY ON THE TV", b -> store(b, FireTv.SPOTIFY, "Spotify"));
                youtubeChoice(youtube, have.contains(FireTv.SMARTTUBE), yt);
            });
        });
    }

    private TextView installButton(LinearLayout cards, String label, Tap tap) {
        TextView b = Ui.button(this, label, true);
        b.setTextSize(12);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            tap.on(b);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 10);
        cards.addView(b, 0, lp);
        return b;
    }

    private void remove(View v) {
        if (v.getParent() instanceof LinearLayout) ((LinearLayout) v.getParent()).removeView(v);
    }

    /** With the official YouTube app on the TV: SmartTube or YouTube (SmartTube is installed when picked). */
    private void youtubeChoice(LinearLayout cards, boolean hasSmart, String yt) {
        String key = Config.YOUTUBE_APP + tvId;
        if (yt == null) {
            if (!cfg.get(key).isEmpty()) cfg.put(key, ""); // the official app is gone
            if (!hasSmart) {
                installButton(cards, "INSTALL SMARTTUBE ON THE TV",
                    b -> download(b, TvInstall.SMARTTUBE_RELEASES, TvInstall.SMARTTUBE_APK, "SmartTube", () -> remove(b)));
            }
            return;
        }
        if (!hasSmart && cfg.get(key).isEmpty()) cfg.put(key, yt); // only YouTube there: use it
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView with = Ui.text(this, "PLAY YOUTUBE WITH", 11, Ui.DIM, true);
        with.setLetterSpacing(0.08f);
        box.addView(with);
        LinearLayout row = new LinearLayout(this);
        TextView smart = Ui.button(this, hasSmart ? "SmartTube" : "SmartTube ↓", false), off = Ui.button(this, "YouTube", false);
        smart.setTextSize(13);
        off.setTextSize(13);
        Runnable paint = () -> {
            boolean useYt = cfg.get(key).equals(yt);
            Ui.style(smart, !useYt);
            Ui.style(off, useYt);
            smart.setContentDescription("SmartTube" + (useYt ? "" : ", selected") + (hasSmart ? "" : ", installs it on the TV"));
            off.setContentDescription("YouTube" + (useYt ? ", selected" : ""));
            tv.youtube(cfg.get(key).isEmpty() ? null : cfg.get(key)); // this TV's player follows the choice now
        };
        boolean[] smartThere = {hasSmart};
        smart.setOnClickListener(v -> {
            Ui.haptic(v);
            if (smartThere[0]) {
                cfg.put(key, "");
                paint.run();
            } else {
                download(smart, TvInstall.SMARTTUBE_RELEASES, TvInstall.SMARTTUBE_APK, "SmartTube", () -> {
                    smartThere[0] = true;
                    smart.setText("SmartTube");
                    cfg.put(key, "");
                    paint.run();
                });
            }
        });
        off.setOnClickListener(v -> {
            Ui.haptic(v);
            cfg.put(key, yt);
            paint.run();
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.rightMargin = Ui.dp(this, 8);
        row.addView(smart, half);
        row.addView(off, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.dp(this, 6);
        box.addView(row, rl);
        paint.run();
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.bottomMargin = Ui.dp(this, 10);
        cards.addView(box, 0, bl);
    }

    /** The app's page in the TV's store; the user confirms there with the TV remote. */
    private void store(TextView b, String pkg, String name) {
        io.execute(() -> {
            String msg;
            try {
                tv.openStore(pkg);
                msg = name + " is open in the TV's store: confirm there with the TV remote";
            } catch (IOException e) {
                msg = MainActivity.AWAY;
            }
            String m = msg;
            runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, m, Toast.LENGTH_LONG).show(); });
        });
    }

    /** Latest release from GitHub → this phone → the TV's installer, with progress on the button. */
    private void download(TextView b, String releases, String prefix, String name, Runnable done) {
        CharSequence label = b.getText();
        b.setEnabled(false);
        b.setText("FINDING " + name.toUpperCase() + "…");
        io.execute(() -> {
            java.io.File f = new java.io.File(getCacheDir(), "tv-install.apk");
            String msg;
            boolean ok = false;
            try {
                byte[] json = fetch(releases);
                if (json == null) throw new IOException("GitHub didn't answer");
                String url = TvInstall.pickApk(new String(json, "UTF-8"), prefix, tv.abi());
                if (url == null) throw new IOException("no " + name + " download for this TV");
                save(url, f, pct -> runOnUiThread(() -> b.setText("DOWNLOADING " + name.toUpperCase() + "… " + pct + "%")));
                runOnUiThread(() -> b.setText("INSTALLING ON THE TV…"));
                tv.install(f);
                ok = true;
                msg = name + " is installed on the TV";
            } catch (IOException e) {
                msg = "Couldn't install " + name + ": " + e.getMessage();
            } finally {
                f.delete();
            }
            boolean installed = ok;
            String m = msg;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                b.setEnabled(true);
                b.setText(label);
                if (installed) done.run();
            });
        });
    }

    private interface Progress {
        void at(int percent);
    }

    /** Downloads to a file, telling the percentage as it goes. */
    private static void save(String url, java.io.File to, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("download failed (HTTP " + c.getResponseCode() + ")");
            long size = c.getContentLengthLong(), got = 0;
            int last = -1;
            try (InputStream in = c.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(to)) {
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) {
                    out.write(b, 0, n);
                    got += n;
                    int pct = size > 0 ? (int) (got * 100 / size) : 0;
                    if (pct != last) progress.at(last = pct);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Runs on the TV: switches it on first if it's off, then says what happened. */
    private void run(TvAction action) {
        io.execute(() -> {
            String said;
            try {
                if (tv.tvState(tv.state()) != State.ON) {
                    tv.wake();
                    Thread.sleep(3000); // let the TV and the stick come up before the app opens
                }
                said = action.run();
            } catch (IOException e) {
                said = e.getMessage() != null && !e.getMessage().startsWith("Failed") ? e.getMessage() : MainActivity.AWAY;
            } catch (InterruptedException e) {
                return;
            }
            String msg = said;
            runOnUiThread(() -> {
                if (!isDestroyed()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private GradientDrawable gradient(int from, int to) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {from, to});
    }

    /** Background thread: decodes and shows a picture (null bytes = nothing to show). */
    private void show(ImageView v, byte[] bytes) {
        if (bytes == null) return;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (b != null) runOnUiThread(() -> {
            if (isDestroyed()) return;
            v.setAlpha(0f);
            v.setImageBitmap(b);
            v.animate().alpha(1f).setStartDelay(0).setDuration(300); // pictures fade in as they arrive
        });
    }

    /** A public picture (e.g. YouTube's), or null. */
    private static byte[] fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toByteArray();
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
