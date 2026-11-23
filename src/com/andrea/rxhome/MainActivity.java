package com.andrea.rxhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** The one screen: server, TV, lights, scenes. Paints last-known state at once, then checks each device. */
public class MainActivity extends Activity {

    static final String AWAY = "Can't reach it: not on home Wi-Fi or WireGuard";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Config cfg;
    private int generation;

    private TextView serverStatus, serverStats, openJellyfin;
    private final Confirm serverConfirm = new Confirm();
    // This refresh's server and FireStick results: both unreachable = away → WireGuard (see Vpn).
    private State genServer, genStick;
    private Boolean genHome; // any home device answered this refresh
    private long vpnTriedAt, scannedAt;
    private boolean splashPending;
    private String vpnNote; // shown on the server card while WireGuard connects, or why it didn't
    private final Map<String, LightRow> rows = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        splashPending = state == null; // a fresh start, not a rotation or a return
        setContentView(build()); // once: coming back only redraws what changed (renderParts)
        Ui.cascade(homeCol, splashPending ? Splash.DRAW_MS : 0); // cards rise in as the splash ends
        if (checkSelfPermission(Vpn.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Vpn.PERMISSION}, 1); // lets rxhome switch on the WireGuard tunnel
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back counts as wanting the tunnel again (TunnelWatch may have switched it off): the
        // once-a-minute limit is only for rxhome's own re-checks.
        vpnTriedAt = -Vpn.RETRY_MS;
        // Watches Wi-Fi changes and which app is in front while you use rxhome or Jellyfin.
        startForegroundService(new Intent(this, TunnelWatch.class));
        renderParts(); // Settings may have changed the lights or scenes
        if (!cfg.get(POWER_MODE).isEmpty()) followPower();
        if (splashPending) {
            splashPending = false;
            // Over the whole window (content gets rebuilt while loading); skipped with animations off.
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                new SplashView(this).play((android.view.ViewGroup) getWindow().getDecorView());
            }
        }
        refresh();
        onScreen = this;
        maybeCheckUpdate();
    }

    @Override
    protected void onPause() {
        if (onScreen == this) onScreen = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout lightsSlot, scenesSlot, homeCol;
    private String shownLights, shownScenes;

    /** Redraws the lights card and the scenes only when their settings changed (keeps scroll, no flash). */
    private void renderParts() {
        String l = cfg.get(Config.LIGHTS), s = cfg.get(Config.SCENES);
        if (!l.equals(shownLights)) {
            shownLights = l;
            lightsSlot.removeAllViews();
            lightsSlot.addView(lightsTile());
        }
        if (!s.equals(shownScenes)) {
            shownScenes = s;
            scenesSlot.removeAllViews();
            scenesSlot.addView(scenes());
        }
    }

    private View build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(header());
        col.addView(serverTile());
        tvBox = new LinearLayout(this);
        tvBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(tvBox);
        tvCards();
        lightsSlot = new LinearLayout(this);
        lightsSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(lightsSlot);
        scenesSlot = new LinearLayout(this);
        scenesSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(scenesSlot);
        Ui.animateLayout(col);
        homeCol = col;
        shownLights = shownScenes = null;
        renderParts();
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(col);
        return s;
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        SpannableString t = new SpannableString("rxhome");
        t.setSpan(new ForegroundColorSpan(Ui.RED), 2, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView title = Ui.text(this, t, 26, Ui.TEXT, true);
        h.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(Ui.DIM);
        clock.setTypeface(Ui.font(this, false));
        clock.setTextSize(14);
        h.addView(clock);
        TextView refresh = Ui.text(this, "  ↻", 22, Ui.TEXT, false);
        refresh.setContentDescription("Refresh");
        refresh.setOnClickListener(v -> { Ui.haptic(v); refresh(); });
        h.addView(refresh);
        TextView settings = Ui.text(this, "  ⚙", 22, Ui.TEXT, false);
        settings.setContentDescription("Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        h.addView(settings);
        h.setPadding(0, 0, 0, Ui.dp(this, 16));
        return h;
    }

    // --- server ---

    private View serverTile() {
        LinearLayout tile = Ui.tile(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(this, "rexserver", 18, Ui.TEXT, true));
        serverStatus = Ui.text(this, "", 13, Ui.DIM, false);
        serverRing = new StatusRing(this);
        text.addView(statusLine(serverRing, serverStatus));
        serverStats = Ui.text(this, "", 13, Ui.TEXT, false);
        serverStats.setFontFeatureSettings("tnum");
        text.addView(serverStats);
        showStats(null, false); // only fresh stats show: remembered ones flashed up and vanished when the server was off
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView power = Ui.power(this);
        power.setOnClickListener(this::serverPower);
        row.addView(power);
        tile.addView(row);
        // Shown when the server answers and the Jellyfin app is on the phone.
        openJellyfin = Ui.button(this, "OPEN JELLYFIN", false);
        openJellyfin.setOnClickListener(v -> {
            Intent jf = getPackageManager().getLaunchIntentForPackage(Vpn.JELLYFIN);
            if (jf != null) startActivity(jf);
        });
        LinearLayout.LayoutParams jfLp = new LinearLayout.LayoutParams(-1, -2);
        jfLp.topMargin = Ui.dp(this, 12);
        tile.addView(openJellyfin, jfLp);
        paintServer(); // "Checking..." until the first real check (a remembered state could be stale)
        return tile;
    }

    private State shownServer = State.UNREACHABLE;
    private boolean serverFresh;
    private boolean homeSeen; // other home devices answer: a silent server is off, not unknown
    private boolean statsSeen; // fresh stats this refresh (not the remembered ones): with Jellyfin silent = starting

    private void showServer(State s, boolean fresh) {
        shownServer = s;
        if (fresh) serverFresh = true; // the button only shows on a real check, not the remembered state
        paintServer();
    }

    private StatusRing serverRing;

    /** A status ring, then its text. */
    private LinearLayout statusLine(StatusRing ring, TextView status) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.dp(this, 8);
        line.addView(ring, lp);
        line.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        return line;
    }

    static final String POWER_MODE = "power_mode", POWER_AT = "power_at";

    /** While a power-on / shutdown is followed: repaint every second (countdown), check the server every 3 s. */
    private final Runnable powerTick = new Runnable() {
        private int n;

        @Override public void run() {
            if (cfg.get(POWER_MODE).isEmpty() || isDestroyed()) return;
            paintServer();
            if (n++ % 3 == 0) checkServerNow();
            main.postDelayed(this, 1000);
        }
    };

    private void followPower() {
        main.removeCallbacks(powerTick);
        main.post(powerTick);
    }

    /** Just the server (Jellyfin + stats), not a full refresh. */
    private void checkServerNow() {
        String server = cfg.get(Config.SERVER), pub = cfg.get(Config.SERVER_PUBLIC);
        int sid = statusChecks.next(), mid = statsChecks.next();
        io.execute(() -> {
            State st = Server.status(server, pub);
            String stats = Metrics.fetch(metricsUrl(server));
            ui(() -> {
                if (statsChecks.current(mid)) showStats(stats, true);
                if (statusChecks.current(sid)) showServer(st, true);
            });
        });
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void paintServer() {
        if (openJellyfin == null) return; // the card is still being built (remembered stats come first)
        State s = shownServer;
        if (s == State.ON) vpnNote = null;
        openJellyfin.setVisibility(s == State.ON && serverFresh && getPackageManager().getLaunchIntentForPackage(Vpn.JELLYFIN) != null
            ? View.VISIBLE : View.GONE);
        String mode = cfg.get(POWER_MODE);
        if (!mode.isEmpty()) {
            long elapsed = System.currentTimeMillis() - parseLong(cfg.get(POWER_AT));
            String now = serverFresh ? Server.label(s, statsSeen, homeSeen) : "";
            if (mode.equals("on") && now.equals("online")) {
                cfg.put(Config.BOOTS, Boot.record(cfg.get(Config.BOOTS), elapsed)); // learn this boot's time
                cfg.put(POWER_MODE, "");
            } else if (mode.equals("off") && (now.equals("off") || now.equals("unknown"))) {
                cfg.put(POWER_MODE, "");
            } else if (Boot.expired(elapsed)) {
                cfg.put(POWER_MODE, "");
            } else {
                serverStatus.setText(mode.equals("on") ? Boot.label(elapsed, Boot.estimate(cfg.get(Config.BOOTS))) : "Shutting down…");
                serverStatus.setTextColor(Ui.TEXT);
                serverStatus.setAlpha(1f);
                serverRing.set(StatusRing.BUSY);
                return;
            }
        }
        if (!serverFresh) {
            serverStatus.setText("Checking…");
            serverStatus.setTextColor(Ui.DIM);
            serverStatus.setAlpha(1f);
            serverRing.set(StatusRing.BUSY);
            return;
        }
        String label = Server.label(s, statsSeen, homeSeen);
        serverStatus.setText(label.equals("online") ? "ONLINE" : label.equals("starting") ? "STARTING…"
            : label.equals("off") ? "OFF" : vpnNote != null ? vpnNote : "Status unknown: off, or not on home Wi-Fi / WireGuard");
        serverStatus.setTextColor(label.equals("online") ? Ui.GREEN : label.equals("off") ? Ui.RED
            : label.equals("starting") ? Ui.TEXT : Ui.DIM);
        serverRing.set(label.equals("online") ? StatusRing.ON : label.equals("off") ? StatusRing.OFF
            : label.equals("starting") || vpnNote != null && vpnNote.startsWith("Away") ? StatusRing.BUSY : StatusRing.UNKNOWN);
        Ui.reachable(serverStatus, !label.equals("unknown"));
    }

    /** CPU · temp · RAM · uptime line; hidden when node_exporter can't be reached. */
    /** Checks overlap (refresh + every 3 s while booting): only the newest answer counts. */
    private final Steady statusChecks = new Steady(), statsChecks = new Steady();

    private void showStats(String stats, boolean fresh) {
        if (fresh) stats = statsChecks.stats(stats); // one missed reading keeps the line; two hide it
        Ui.setText(serverStats, stats == null ? "" : stats);
        serverStats.setVisibility(stats == null ? View.GONE : View.VISIBLE);
        statsSeen = fresh && stats != null;
        paintServer();
    }

    // shortcut: node_exporter assumed on the Jellyfin LAN host at :9100; make it a setting if it ever moves.
    static String metricsUrl(String jellyfinLan) {
        try {
            return "http://" + new java.net.URL(jellyfinLan).getHost() + ":9100/metrics";
        } catch (java.net.MalformedURLException e) {
            return "";
        }
    }

    private void serverPower(View v) {
        Ui.haptic(v);
        Ui.burst(v);
        SwitchBot sb = cfg.switchBot();
        String bot = cfg.get(Config.SB_BOT);
        if (sb == null || bot.isEmpty()) {
            toast("Add your SwitchBot token, secret and Bot in Settings");
            return;
        }
        // It's a hard press on the power button: ask for a second tap.
        long now = android.os.SystemClock.uptimeMillis();
        boolean wasArmed = serverConfirm.armed(now);
        if (!serverConfirm.tap(now)) {
            if (wasArmed) return; // a jittery double-tap: ignored
            serverStatus.setText("Tap the power button again to press the server's button");
            Ui.reachable(serverStatus, true);
            main.postDelayed(() -> {
                if (!serverConfirm.armed(android.os.SystemClock.uptimeMillis())) {
                    paintServer();
                }
            }, Confirm.MAX_MS + 50);
            return;
        }
        serverStatus.setText("Pressing…");
        boolean wasOnline = serverFresh && shownServer == State.ON;
        io.execute(() -> {
            try {
                sb.command(bot, "press");
                cfg.put(POWER_MODE, wasOnline ? "off" : "on");
                cfg.put(POWER_AT, Long.toString(System.currentTimeMillis()));
                ui(() -> {
                    toast("Pressed the server's power button");
                    followPower();
                });
            } catch (IOException e) {
                ui(() -> toast("SwitchBot: " + e.getMessage()));
            }
            ui(this::refresh);
        });
    }

    // --- TV ---

    // One card per saved TV, in the order set in Settings → TVs → Rename / reorder.
    private LinearLayout tvBox;
    private final java.util.List<TvCard> cards = new java.util.ArrayList<>();
    private String cardsShown;

    /** (Re)builds the TV cards when the saved TVs, their names or their order changed. */
    private void tvCards() {
        java.util.List<Tvs.Saved> list = cfg.saved();
        StringBuilder k = new StringBuilder();
        for (Tvs.Saved t : list) k.append(t.uid).append('|').append(t.name).append(';');
        if (k.toString().equals(cardsShown)) return;
        cardsShown = k.toString();
        tvBox.removeAllViews();
        cards.clear();
        if (list.isEmpty()) {
            TvCard c = new TvCard(null);
            Ui.setText(c.status, "No TV yet: tap here to add one");
            c.status.setTextColor(Ui.DIM);
            c.ring.set(StatusRing.UNKNOWN);
            c.status.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            tvBox.addView(c.tile);
            return;
        }
        // One TV in view; swipe for the next (order from Settings). The one shown last time comes back.
        Pager pager = new Pager(this);
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        int shown = 0;
        for (int i = 0; i < list.size(); i++) {
            TvCard c = new TvCard(list.get(i));
            cards.add(c);
            c.tile.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -2));
            pager.add(c.tile);
            if (list.get(i).uid.equals(cfg.get(TV_SHOWN))) shown = i;
            View dot = new View(this);
            int d = Ui.dp(this, 6);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(d, d);
            dl.setMargins(d / 2, 0, d / 2, 0);
            dots.addView(dot, dl);
        }
        pager.onPage = page -> {
            cfg.put(TV_SHOWN, list.get(page).uid);
            paintDots(dots, page);
        };
        pager.show(shown);
        paintDots(dots, shown);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.leftMargin = pl.rightMargin = -pager.gap(); // cards line up with the others; a gap shows while swiping
        tvBox.addView(pager, pl);
        if (list.size() > 1) {
            LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, -2);
            dotsLp.topMargin = Ui.dp(this, 8);
            tvBox.addView(dots, dotsLp);
        }
        tvBox.addView(new View(this), new LinearLayout.LayoutParams(1, Ui.dp(this, 12))); // the cards' usual gap below
    }

    static final String TV_SHOWN = "tv_shown"; // the TV card in view on the home screen

    /** The pager's dots: the TV in view red, the others dim. */
    private void paintDots(LinearLayout dots, int page) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(i == page ? Ui.RED : 0xFF3A3A44);
            dots.getChildAt(i).setBackground(g);
        }
        dots.setContentDescription("TV " + (page + 1) + " of " + dots.getChildCount());
    }

    private final class TvCard {
        final Tvs.Saved saved;
        final LinearLayout tile;
        final TextView status;
        final StatusRing ring;

        TvCard(Tvs.Saved saved) {
            this.saved = saved;
            tile = Ui.tile(MainActivity.this);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout text = new LinearLayout(MainActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(Ui.text(MainActivity.this, saved == null ? "Tv" : saved.name, 18, Ui.TEXT, true));
            status = Ui.text(MainActivity.this, "Checking…", 13, Ui.DIM, false); // a remembered state could be stale
            ring = new StatusRing(MainActivity.this);
            ring.set(StatusRing.BUSY);
            text.addView(statusLine(ring, status));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            tile.addView(row);
            if (saved == null) return;
            tile.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, TvInfoActivity.class)
                .putExtra(TvInfoActivity.UID, saved.uid))); // the card itself (not its buttons): this TV's settings
            TextView power = Ui.power(MainActivity.this);
            power.setContentDescription("Power " + saved.name);
            power.setOnClickListener(this::power);
            row.addView(power);

            LinearLayout buttons = new LinearLayout(MainActivity.this);
            buttons.setPadding(0, Ui.dp(MainActivity.this, 12), 0, 0);
            TextView remote = Ui.button(MainActivity.this, "REMOTE", false);
            remote.setOnClickListener(v -> open(RemoteActivity.class));
            TextView apps = Ui.button(MainActivity.this, "APPS", false);
            apps.setOnClickListener(v -> open(TvAppsActivity.class));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = Ui.dp(MainActivity.this, 8);
            buttons.addView(remote, lp);
            buttons.addView(apps, new LinearLayout.LayoutParams(0, -2, 1));
            tile.addView(buttons);
            if (!tv().can("apps")) { // e.g. a Sky box alone, or a TV that can't list apps: greyed, not a dead button
                apps.setEnabled(false);
                apps.setAlpha(0.4f);
            }
        }

        Tv tv() {
            return cfg.tv(saved);
        }

        /** Remote and Apps work on the TV in use: this one, from now on. */
        void open(Class<?> screen) {
            cfg.setActive(saved.uid);
            startActivity(new Intent(MainActivity.this, screen));
        }

        void show(State s) {
            Tv tv = tv(); // a TV's state is its own; on its own, a FireStick's or Sky box's
            String who = tv.hasScreen() ? "" : tv.hasStick() ? " · FireStick" : " · Sky";
            Ui.setText(status, s == State.ON ? "On" + who
                : s == State.OFF ? (tv.hasScreen() ? "Off" : "Standby" + who) : AWAY);
            status.setTextColor(s == State.ON ? Ui.GREEN : s == State.OFF ? Ui.RED : Ui.DIM);
            ring.set(s == State.ON ? StatusRing.ON : s == State.OFF ? StatusRing.OFF : StatusRing.UNKNOWN);
            Ui.reachable(tile, s != State.UNREACHABLE);
        }

        void power(View v) {
            Ui.haptic(v);
            Ui.burst(v);
            status.setText("…");
            Tv tv = tv();
            io.execute(() -> {
                try {
                    tv.togglePower(tv.state());
                } catch (IOException e) {
                    ui(() -> toast(adbError(e)));
                }
                State s = tv.tvState(tv.state());
                ui(() -> show(s));
            });
        }
    }

    static String adbError(IOException e) {
        return e instanceof Adb.AdbAuthException ? "The TV's prompt wasn't accepted. Tap ↻ to show it again" : AWAY;
    }

    // --- lights ---

    private final class LightRow {
        final LinearLayout view;
        final SeekBar bar;
        final Switch toggle;
        final View dot; // the light's colour; tap to change it (colour lights only)
        JSONObject info;
        final Tapo tapo;
        final String name;
        boolean updating;
        String problem;

        LightRow(String name, Tapo tapo) {
            this.name = name;
            this.tapo = tapo;
            view = new LinearLayout(MainActivity.this);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(0, Ui.dp(MainActivity.this, 6), 0, Ui.dp(MainActivity.this, 6));
            view.addView(Ui.text(MainActivity.this, name, 15, Ui.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
            bar = new SeekBar(MainActivity.this);
            bar.setMax(100);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
            bar.setContentDescription(name + " brightness");
            dot = new View(MainActivity.this);
            dot.setVisibility(View.GONE);
            dot.setContentDescription(name + " colour");
            dot.setOnClickListener(v -> { Ui.haptic(v); pickColor(); });
            int d = Ui.dp(MainActivity.this, 28);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(d, d);
            dotLp.rightMargin = Ui.dp(MainActivity.this, 6);
            view.addView(dot, dotLp);
            view.addView(bar, new LinearLayout.LayoutParams(Ui.dp(MainActivity.this, 120), -2));
            toggle = new Switch(MainActivity.this);
            toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
            toggle.setTrackTintList(new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {Ui.RED, Ui.BUTTON}));
            toggle.setContentDescription(name);
            view.addView(toggle);

            String last = cfg.prefs.getString("light_" + name, "");
            if (last.contains(",")) show(last.startsWith("1"), Integer.parseInt(last.split(",")[1]), true);
            else Ui.reachable(view, false);

            toggle.setOnCheckedChangeListener((b, on) -> {
                if (!updating) send(on, null);
            });
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int v, boolean user) {}
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {
                    if (!updating) send(true, Math.max(1, s.getProgress()));
                }
            });
        }

        void show(boolean on, int brightness, boolean reachable) {
            updating = true;
            toggle.setChecked(on);
            bar.setProgress(brightness, true);
            updating = false;
            Ui.reachable(view, reachable);
            cfg.prefs.edit().putString("light_" + name, (on ? "1," : "0,") + brightness).apply();
        }

        void send(boolean on, Integer brightness) {
            Ui.haptic(view);
            io.execute(() -> {
                try {
                    tapo.set(on, brightness, null);
                    load();
                } catch (IOException e) {
                    ui(() -> { Ui.reachable(view, false); toast(name + ": " + e.getMessage()); });
                }
            });
        }

        /** The colour a light shows now: white mode (colour_temp > 0) as warm/cool white, else its hue. */
        int currentColor(JSONObject i) {
            int k = i.optInt("color_temp", 0);
            if (k > 0) return k < 4000 ? 0xFFFFC98A : 0xFFEAF2FF;
            return android.graphics.Color.HSVToColor(new float[] {i.optInt("hue"), i.optInt("saturation") / 100f, 1f});
        }

        /** A colour wheel (drag: the light follows) and warm/cool white. */
        void pickColor() {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(MainActivity.this, 16);
            box.setPadding(p, p / 2, p, 0);

            ColorWheelView wheel = new ColorWheelView(MainActivity.this);
            if (info != null && info.optInt("color_temp", 0) == 0) wheel.setColor(info.optInt("hue"), info.optInt("saturation", 100));
            java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
            long[] lastSent = {0};
            wheel.listener = (h, sat, done) -> {
                long now = android.os.SystemClock.uptimeMillis();
                if (done) {
                    send(() -> tapo.color(h, sat)); // the final colour always goes, then the row refreshes
                } else if (now - lastSent[0] > 250 && busy.compareAndSet(false, true)) {
                    // While dragging: one change at a time, so slow lights don't queue up behind the finger.
                    lastSent[0] = now;
                    io.execute(() -> {
                        try {
                            tapo.color(h, sat);
                        } catch (IOException e) {
                            // The final colour on release still goes.
                        } finally {
                            busy.set(false);
                        }
                    });
                }
            };
            box.addView(wheel);

            boolean temp = info != null && info.has("color_temp_range");
            LinearLayout whitesRow = new LinearLayout(MainActivity.this);
            TextView warm = Ui.button(MainActivity.this, "WARM WHITE", false);
            warm.setOnClickListener(v -> send(() -> { if (temp) tapo.set(true, null, 2700); else tapo.color(30, 35); }));
            TextView cool = Ui.button(MainActivity.this, "COOL WHITE", false);
            cool.setOnClickListener(v -> send(() -> { if (temp) tapo.set(true, null, 6500); else tapo.color(210, 8); }));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1);
            wl.rightMargin = Ui.dp(MainActivity.this, 8);
            whitesRow.addView(warm, wl);
            whitesRow.addView(cool, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.topMargin = Ui.dp(MainActivity.this, 12);
            box.addView(whitesRow, rowLp);

            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name)
                .setView(box)
                .setNegativeButton("Done", null)
                .show();
        }

        private GradientDrawable circle(int color) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            g.setStroke(Ui.dp(MainActivity.this, 2), Ui.BUTTON);
            return g;
        }

        private void send(TapoCall call) {
            io.execute(() -> {
                try {
                    call.run();
                    load();
                } catch (IOException e) {
                    ui(() -> toast(name + ": " + e.getMessage()));
                }
            });
        }

        /** Background: read the light's state. */
        void load() {
            try {
                JSONObject i = tapo.info();
                info = i;
                boolean on = i.optBoolean("device_on");
                int b = i.optInt("brightness", on ? 100 : 0);
                ui(() -> {
                    show(on, b, true);
                    bar.setEnabled(i.has("brightness"));
                    if (Tapo.hasColor(i)) {
                        dot.setBackground(circle(currentColor(i)));
                        dot.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException e) {
                problem = e.getMessage();
                ui(() -> Ui.reachable(view, false));
            }
        }
    }

    private interface TapoCall {
        void run() throws IOException;
    }

    private View lightsTile() {
        LinearLayout tile = Ui.tile(this);
        tile.addView(Ui.text(this, "Lights", 18, Ui.TEXT, true));
        rows.clear();
        Map<String, Tapo> lights = cfg.lights();
        if (lights.isEmpty()) {
            tile.addView(Ui.text(this, "Looking for Tapo lights on this Wi-Fi…", 13, Ui.DIM, false));
        }
        for (Map.Entry<String, Tapo> e : lights.entrySet()) {
            LightRow r = new LightRow(e.getKey(), e.getValue());
            rows.put(e.getKey(), r);
            r.view.setOnClickListener(v -> toast(r.problem != null ? r.problem : AWAY));
            tile.addView(r.view);
        }
        return tile;
    }

    // --- scenes ---

    private View scenes() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        List<Scenes.Scene> list;
        try {
            list = Scenes.parse(cfg.get(Config.SCENES));
        } catch (ParseException e) {
            col.addView(Ui.text(this, "Scenes: " + e.getMessage() + " (fix it in Settings)", 13, Ui.RED, false));
            return col;
        }
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = Ui.dp(this, 8);
                col.addView(row, rp);
            }
            Scenes.Scene scene = list.get(i);
            TextView b = Ui.button(this, scene.name, i == 0);
            b.setMinHeight(Ui.dp(this, 64));
            b.setOnClickListener(v -> runScene(b, scene));
            // Own height (min 64 dp), not the row's: an odd last row holds only this and an empty
            // filler, and "as tall as the row" collapsed it to the filler's height.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 0) lp.rightMargin = Ui.dp(this, 8);
            row.addView(b, lp);
        }
        // Keep an odd last scene half-width, like the others.
        if (list.size() % 2 == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
        return col;
    }

    /**
     * "Choose when run": a card with what the app can open (for RxFilms, every Continue watching and Next up
     * item); waits for a pick on the scene's thread. Null = cancelled or no answer in 2 minutes.
     */
    private String askWhat(String pkg) {
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        Jellyfin jf = cfg.jellyfin();
        for (Map.Entry<String, String> c : Scenes.contents(pkg).entrySet()) {
            if (c.getKey().equals("ask")) continue;
            if ((c.getKey().equals("resume") || c.getKey().equals("nextup")) && jf != null) {
                try {
                    String prefix = c.getKey().equals("resume") ? "Continue: " : "Next up: ";
                    for (Jellyfin.Item it : c.getKey().equals("resume") ? jf.resumeAll() : jf.nextUpAll()) {
                        labels.add(prefix + it.label());
                        values.add("item:" + it.id);
                    }
                } catch (IOException e) {
                    // Jellyfin not answering: those items just aren't offered.
                }
                continue;
            }
            if (c.getKey().equals("resume") || c.getKey().equals("nextup")) continue; // not signed in
            labels.add(c.getValue());
            values.add(c.getKey());
        }
        java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
        String[] pick = {null};
        ui(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Open " + Scenes.apps().getOrDefault(pkg, pkg) + " on the TV")
            .setItems(labels.toArray(new String[0]), (d, w) -> pick[0] = values.get(w))
            .setOnDismissListener(d -> answered.countDown())
            .show());
        try {
            answered.await(2, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            return null;
        }
        return pick[0];
    }

    private void runScene(TextView b, Scenes.Scene scene) {
        if (!b.isEnabled()) return;
        Ui.haptic(b);
        b.setEnabled(false);
        b.setText("Running…");
        android.animation.ValueAnimator shimmer = Ui.shimmer(b);
        String server = cfg.get(Config.SERVER), pub = cfg.get(Config.SERVER_PUBLIC);
        SceneRunner runner = new SceneRunner(cfg.lights(), cfg.tv(), cfg.switchBot(), cfg.get(Config.SB_BOT),
            () -> Server.effective(Server.status(server, pub), Metrics.fetch(metricsUrl(server)) != null),
            cfg.jellyfin(), this::askWhat, () -> ui(() -> startActivity(new Intent(this, RemoteActivity.class))));
        io.execute(() -> {
            int[] r = runner.run(scene);
            ui(() -> {
                shimmer.cancel();
                Ui.pop(b);
                b.setText(r[0] == r[1] ? "Done" : r[0] + "/" + r[1] + " done");
                main.postDelayed(() -> { b.setText(scene.name); b.setEnabled(true); }, 3000);
                refresh();
            });
        });
    }

    // --- refresh ---

    private void refresh() {
        int gen = ++generation;
        genServer = genStick = null;
        genHome = null;
        String server = cfg.get(Config.SERVER), pub = cfg.get(Config.SERVER_PUBLIC);
        java.util.List<String> home = cfg.homeDevices();
        io.execute(() -> {
            boolean h = Discovery.anyAnswers(home, 1500);
            ui(() -> {
                if (gen != generation) return;
                genHome = h;
                homeSeen = h;
                paintServer();
                afterChecks();
            });
        });
        int sid = statusChecks.next(), mid = statsChecks.next();
        io.execute(() -> {
            State s = Server.status(server, pub);
            ui(() -> {
                if (gen != generation || !statusChecks.current(sid)) return; // a newer check already answered
                showServer(s, true);
                genServer = s;
                afterChecks();
            });
        });
        io.execute(() -> {
            String stats = Metrics.fetch(metricsUrl(server));
            ui(() -> { if (gen == generation && statsChecks.current(mid)) showStats(stats, true); });
        });
        tvCards();
        if (cards.isEmpty()) genStick = State.UNREACHABLE; // nothing to ask: home is told by the rest
        int[] pending = {cards.size()};
        State[] best = {State.UNREACHABLE};
        for (TvCard c : cards) {
            Tv tv = c.tv();
            tv.onKeyPrompt(() -> ui(() -> {
                Ui.reachable(c.tile, true);
                c.status.setText("On the TV: tick \"Always allow\", then Allow");
                c.status.setTextColor(Ui.RED);
            }));
            io.execute(() -> {
                State stick;
                State shown;
                String why = null;
                try {
                    stick = tv.checkedState();
                    shown = tv.tvState(stick);
                } catch (IOException e) {
                    // A key prompt means the stick answered: we're home.
                    stick = e instanceof Adb.AdbAuthException ? State.ON : State.UNREACHABLE;
                    shown = State.UNREACHABLE;
                    why = adbError(e);
                }
                State st = stick, sh = shown;
                String w = why;
                ui(() -> {
                    if (gen != generation) return;
                    c.show(sh);
                    if (w != null) c.status.setText(w);
                    if (st != State.UNREACHABLE) best[0] = st; // home if any TV answers
                    if (--pending[0] == 0) {
                        genStick = best[0];
                        afterChecks();
                    }
                });
            });
        }
        for (LightRow r : rows.values()) io.execute(r::load);
        io.execute(this::findLights);
    }

    /**
     * Both home checks are in. Something silent while on Wi-Fi: maybe it moved, so scan for it (at most
     * once a minute) and refresh if found. Nothing new: maybe we're away, so WireGuard (maybeVpn).
     */
    private void afterChecks() {
        if (genServer == null || genStick == null || genHome == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean missing = genServer == State.UNREACHABLE || genStick == State.UNREACHABLE;
        if (missing && now - scannedAt > 60_000) {
            scannedAt = now;
            State server = genServer, stick = genStick;
            io.execute(() -> {
                String me = Discovery.wifiIp();
                if (me != null && (cfg.found(Discovery.find(me, cfg.adbKey())) | movedTv(me))) {
                    ui(this::refresh);
                } else {
                    ui(() -> maybeVpn(server, stick));
                }
            });
            return;
        }
        maybeVpn(genServer, genStick);
    }

    /** Scans for saved TVs; true if the active one moved or another saved one is the one here. */
    private boolean movedTv(String me) {
        String before = Tvs.format(cfg.saved()) + cfg.get(Config.TV_ACTIVE);
        java.util.List<Discovery.TvFound> found = Discovery.findTvs(me, cfg.adbKey());
        StringBuilder seen = new StringBuilder();
        for (Discovery.TvFound f : found) seen.append(' ').append(f.brand).append('@').append(f.ip).append(f.id == null ? "(untrusted)" : "");
        System.err.println("rxhome-tv: scan found" + seen + "; saved here: " + cfg.foundTvs(found).size());
        return !before.equals(Tvs.format(cfg.saved()) + cfg.get(Config.TV_ACTIVE));
    }

    /** Away from home (server and FireStick both silent): switch on the "rxfilms" WireGuard tunnel. */
    private void maybeVpn(State server, State stick) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!Vpn.shouldStart(Boolean.TRUE.equals(genHome), server == State.ON, now, vpnTriedAt)) return;
        vpnTriedAt = now;
        if (checkSelfPermission(Vpn.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Vpn.PERMISSION}, 1);
            return;
        }
        // Needs "Allow remote control apps" on in WireGuard's settings, or it's silently ignored.
        System.err.println("rxhome-vpn: asking WireGuard to bring up " + Vpn.TUNNEL);
        sendBroadcast(new Intent(Vpn.ACTION_UP).setPackage(Vpn.PACKAGE).putExtra("tunnel", Vpn.TUNNEL));
        // rxhome's tunnel: TunnelWatch may switch it off again (home Wi-Fi, or rxhome and Jellyfin left).
        cfg.put(TunnelWatch.OURS, "1");
        vpnNote = "Away from home: connecting WireGuard (" + Vpn.TUNNEL + ")…";
        showServer(State.UNREACHABLE, false);
        // The handshake home can take ~20 s: keep checking every 3 s for 30 s, until the server answers.
        for (int i = 1; i <= 10; i++) {
            main.postDelayed(() -> { if (genServer != State.ON && !isDestroyed()) refresh(); }, i * 3000L);
        }
        main.postDelayed(() -> {
            if (isDestroyed() || vpnActive()) return;
            System.err.println("rxhome-vpn: no VPN 6 s after asking");
            vpnNote = "WireGuard didn't start: set its battery use to Unrestricted (Settings → Apps → WireGuard → Battery) and turn on \"Allow remote control apps\" in WireGuard";
            showServer(State.UNREACHABLE, false);
        }, 6000);
    }

    /** True if Android has a VPN up (any app's). */
    private boolean vpnActive() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    /** Background: finds Tapo lights on the Wi-Fi, names them, and redraws if the list changed. */
    private void findLights() {
        try {
            Map<String, String> found = new LinkedHashMap<>();
            for (TapoDiscovery.Found f : TapoDiscovery.discover(1500)) {
                String name = f.fallbackName();
                try {
                    String n = new Tapo(f.ip, cfg.get(Config.TAPO_EMAIL), cfg.get(Config.TAPO_PASSWORD)).info().optString("name");
                    if (!n.trim().isEmpty()) name = n.trim();
                } catch (IOException e) {
                    // Can't log in yet (e.g. Third-Party Compatibility off): keep the placeholder name.
                }
                found.put(f.ip, name);
            }
            String before = cfg.get(Config.LIGHTS);
            String after = TapoDiscovery.merge(before, found);
            if (after.equals(before)) return;
            cfg.put(Config.LIGHTS, after);
            ui(() -> {
                renderParts();
                for (LightRow r : rows.values()) io.execute(r::load);
            });
        } catch (IOException e) {
            // Not on Wi-Fi: nothing to find.
        }
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // --- Updates (see Updater), as in rxVision ---

    private static final long UPDATE_CHECK_MS = 60 * 60_000, UPDATE_LATER_MS = 12 * 60 * 60_000;
    private static MainActivity onScreen;
    private long lastUpdateCheck;
    private AlertDialog updating; // the progress while an update downloads and installs

    /** At most hourly, when the app shows: asks if GitHub has a newer version (not again for a while after "Later"). */
    private void maybeCheckUpdate() {
        if (updating != null && !Updater.busy) hideUpdating(); // back from the system's install screen without installing
        long now = android.os.SystemClock.elapsedRealtime();
        if (Updater.busy || (lastUpdateCheck != 0 && now - lastUpdateCheck < UPDATE_CHECK_MS)) return;
        lastUpdateCheck = now;
        io.execute(() -> {
            try {
                Updater.Release r = Updater.newer(this);
                if (r == null) return;
                if (r.code == cfg.prefs.getInt("updateLater", 0)
                    && System.currentTimeMillis() - cfg.prefs.getLong("updateLaterAt", 0) < UPDATE_LATER_MS) return;
                runOnUiThread(() -> { if (onScreen == this && !Updater.busy) askUpdate(r); });
            } catch (IOException e) {
                android.util.Log.w("rxhome", "update check", e);
            }
        });
    }

    private void askUpdate(Updater.Release r) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Update available")
            .setMessage("A new version of rxhome (" + r.code + ") is ready. Update now?")
            .setPositiveButton("Update", (x, w) -> startUpdate(r))
            .setNegativeButton("Later", (x, w) -> cfg.prefs.edit().putInt("updateLater", r.code)
                .putLong("updateLaterAt", System.currentTimeMillis()).apply())
            .show();
    }

    private void startUpdate(Updater.Release r) {
        Updater.busy = true;
        updating = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Updating rxhome").setMessage("Downloading…").setCancelable(false).show();
        io.execute(() -> {
            try {
                Updater.install(this, r, pct -> runOnUiThread(() -> {
                    if (updating != null) updating.setMessage(pct < 100 ? "Downloading… " + pct + "%" : "Installing…");
                }));
            } catch (IOException | RuntimeException e) {
                Updater.busy = false;
                runOnUiThread(() -> {
                    hideUpdating();
                    Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Updater: the install didn't happen (its message is already toasted). */
    static void updateEnded() {
        MainActivity a = onScreen;
        if (a != null) a.hideUpdating();
    }

    private void hideUpdating() {
        if (updating == null) return;
        updating.dismiss();
        updating = null;
    }
}
