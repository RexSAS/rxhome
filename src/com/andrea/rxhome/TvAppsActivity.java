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
    private FireTv tv;

    private interface TvAction {
        String run() throws IOException; // what to tell the user, e.g. "Opening Dune on the TV"
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        tv = cfg.tv();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, "Apps", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "On the TV", 13, Ui.DIM, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 16));
        col.addView(sub);

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

        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
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
            v.animate().alpha(1f).setDuration(300); // pictures fade in as they arrive
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
