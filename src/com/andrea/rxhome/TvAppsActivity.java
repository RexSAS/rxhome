package com.andrea.rxhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's apps, one per row: tap the app to open it on the TV, or a shortcut on its right to go
 * straight somewhere (RxFilms: Continue watching / Next up; Spotify: Liked Songs / DJ X; YouTube: Claude FM).
 */
public class TvAppsActivity extends Activity {

    private static final String DJ_X = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq", LIKED = "spotify:collection:tracks";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private FireTv tv;
    private TextView resumeSub, nextSub;

    private interface TvAction {
        String run() throws IOException; // returns what to tell the user, e.g. "Opening Dune on the TV"
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
        resumeSub = shortcut(films, "Continue watching", () -> openJellyfin(true));
        nextSub = shortcut(films, "Next up", () -> openJellyfin(false));

        LinearLayout spotify = row(col, R.drawable.ic_app_spotify, "Spotify", FireTv.SPOTIFY);
        shortcut(spotify, "Liked Songs", () -> { tv.view(LIKED, FireTv.SPOTIFY); return "Playing your Liked Songs on the TV"; });
        shortcut(spotify, "DJ X", () -> { tv.view(DJ_X, FireTv.SPOTIFY); return "Starting DJ X on the TV"; });

        LinearLayout youtube = row(col, R.drawable.ic_app_youtube, "YouTube", FireTv.SMARTTUBE);
        shortcut(youtube, "Claude FM", () -> { tv.play(FireTv.claudeFmId()); return "Playing Claude FM on the TV"; });

        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
        loadJellyfinLabels();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** One app: logo and name on the left (opens it), its shortcuts stacked on the right. */
    private LinearLayout row(LinearLayout col, int logo, String name, String pkg) {
        LinearLayout tile = Ui.tile(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setGravity(Gravity.CENTER);
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
        app.setContentDescription("Open " + name + " on the TV");
        app.setOnClickListener(v -> {
            Ui.haptic(v);
            run(() -> { tv.launch(pkg); return "Opening " + name + " on the TV"; });
        });
        tile.addView(app, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.VERTICAL);
        tile.addView(shortcuts, new LinearLayout.LayoutParams(0, -2, 1.5f));
        col.addView(tile);
        return shortcuts;
    }

    /** A shortcut button; returns its second line (for what it will open), empty at first. */
    private TextView shortcut(LinearLayout shortcuts, String title, TvAction action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.button(this, "", false).getBackground());
        int p = Ui.dp(this, 12);
        b.setPadding(p, p, p, p);
        b.addView(Ui.text(this, title, 15, Ui.TEXT, true));
        TextView sub = Ui.text(this, "", 12, Ui.DIM, false);
        sub.setVisibility(View.GONE);
        b.addView(sub);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(title);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            run(action);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        shortcuts.addView(b, lp);
        return sub;
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

    private String openJellyfin(boolean resume) throws IOException {
        Jellyfin jf = cfg.jellyfin();
        if (jf == null) return "Sign in to Jellyfin in Settings first";
        Jellyfin.Item it = resume ? jf.resume() : jf.nextUp();
        if (it == null) return resume ? "Nothing to continue" : "Nothing up next";
        tv.openItem(FireTv.RXFILMS, it.id);
        return "Opening " + it.label() + " on the TV";
    }

    /** Shows under the RxFilms shortcuts what they would open. */
    private void loadJellyfinLabels() {
        Jellyfin jf = cfg.jellyfin();
        if (jf == null) {
            resumeSub.setText("Sign in to Jellyfin in Settings");
            resumeSub.setVisibility(View.VISIBLE);
            return;
        }
        io.execute(() -> {
            String r, n;
            try {
                Jellyfin.Item a = jf.resume(), b = jf.nextUp();
                r = a == null ? "Nothing to continue" : a.label();
                n = b == null ? "Nothing up next" : b.label();
            } catch (IOException e) {
                r = n = e.getMessage();
            }
            String rr = r, nn = n;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                resumeSub.setText(rr);
                resumeSub.setVisibility(View.VISIBLE);
                nextSub.setText(nn);
                nextSub.setVisibility(View.VISIBLE);
            });
        });
    }
}
