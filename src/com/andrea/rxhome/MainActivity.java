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
        main.removeCallbacks(autoUpdate);
        main.postDelayed(autoUpdate, AUTO_MS);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(autoUpdate); // only while rxhome is on screen
        super.onPause();
    }

    static final long AUTO_MS = 5000;

    /** While on screen: the Music card every 5 s, everything every 15 s (the ↻ button still works too). */
    private final Runnable autoUpdate = new Runnable() {
        private int n;

        @Override public void run() {
            if (isDestroyed()) return;
            if (++n % 3 == 0) refresh();
            else loadMusic();
            main.postDelayed(this, AUTO_MS);
        }
    };

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout lightsSlot, scenesSlot, musicSlot, tvSlot, homeCol;
    private String shownLights, shownScenes, shownMusic, shownTvs;
    private final List<TvCard> tvCards = new ArrayList<>();

    /** Redraws the lights card and the scenes only when their settings changed (keeps scroll, no flash). */
    private void renderParts() {
        String l = cfg.get(Config.LIGHTS), s = cfg.get(Config.SCENES);
        if (!l.equals(shownLights)) {
            shownLights = l;
            lightsSlot.removeAllViews();
            lightsSlot.addView(lightsTile());
        }
        StringBuilder tvKey = new StringBuilder(Tvs.format(cfg.tvs()));
        for (Tvs.Tv t : cfg.tvs()) tvKey.append('|').append(cfg.philips(t.id) != null); // pairing changes the card
        if (!tvKey.toString().equals(shownTvs)) {
            shownTvs = tvKey.toString();
            tvSlot.removeAllViews();
            tvCards.clear();
            for (Tvs.Tv t : cfg.tvs()) {
                TvCard c = new TvCard(t);
                tvCards.add(c);
                tvSlot.addView(c.tile);
            }
        }
        String m = cfg.spotify() == null ? "" : "connected";
        if (!m.equals(shownMusic)) {
            shownMusic = m;
            musicSlot.removeAllViews();
            if (!m.isEmpty()) {
                musicSlot.addView(musicTile());
                loadMusic();
            }
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
        tvSlot = new LinearLayout(this);
        tvSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(tvSlot);
        musicSlot = new LinearLayout(this);
        musicSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(musicSlot);
        lightsSlot = new LinearLayout(this);
        lightsSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(lightsSlot);
        scenesSlot = new LinearLayout(this);
        scenesSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(scenesSlot);
        Ui.animateLayout(col);
        homeCol = col;
        shownLights = shownScenes = shownMusic = shownTvs = null;
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

    /** One TV's card: its status, power, and (with a stick) its Remote and Apps. */
    private final class TvCard {
        final Tvs.Tv info;
        final LinearLayout tile;
        final TextView status;
        final StatusRing ring;

        TvCard(Tvs.Tv info) {
            this.info = info;
            tile = Ui.tile(MainActivity.this);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout text = new LinearLayout(MainActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(Ui.text(MainActivity.this, info.name, 18, Ui.TEXT, true));
            status = Ui.text(MainActivity.this, "", 13, Ui.DIM, false);
            ring = new StatusRing(MainActivity.this);
            text.addView(statusLine(ring, status));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView power = Ui.power(MainActivity.this);
            power.setContentDescription(info.name + " power");
            power.setOnClickListener(this::power);
            row.addView(power);
            tile.addView(row);

            if (!info.stick.isEmpty()) { // the remote and apps go through the stick
                LinearLayout buttons = new LinearLayout(MainActivity.this);
                buttons.setPadding(0, Ui.dp(MainActivity.this, 12), 0, 0);
                TextView remote = Ui.button(MainActivity.this, "REMOTE", false);
                remote.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, RemoteActivity.class).putExtra("tv", info.id)));
                TextView apps = Ui.button(MainActivity.this, "APPS", false);
                apps.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, TvAppsActivity.class).putExtra("tv", info.id)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.rightMargin = Ui.dp(MainActivity.this, 8);
                buttons.addView(remote, lp);
                buttons.addView(apps, new LinearLayout.LayoutParams(0, -2, 1));
                tile.addView(buttons);
                status.setText("Checking…"); // until the first real check: a remembered state could be stale
                ring.set(StatusRing.BUSY);
            } else {
                status.setText(cfg.philips(info.id) != null ? "Power and volume (add a FireStick for its status)"
                    : "Pair its Philips in Settings → TVs");
                ring.set(StatusRing.UNKNOWN);
            }
        }

        void show(State s) {
            boolean paired = cfg.philips(info.id) != null; // then the state is the TV's own, else the stick's
            Ui.setText(status, s == State.ON ? (paired ? "On" : "On · FireStick")
                : s == State.OFF ? (paired ? "Off" : "Standby · FireStick") : AWAY);
            status.setTextColor(s == State.ON ? Ui.GREEN : s == State.OFF ? Ui.RED : Ui.DIM);
            ring.set(s == State.ON ? StatusRing.ON : s == State.OFF ? StatusRing.OFF : StatusRing.UNKNOWN);
            Ui.reachable(tile, s != State.UNREACHABLE);
        }

        void power(View v) {
            Ui.haptic(v);
            Ui.burst(v);
            byHand(sc -> Scenes.usesTv(sc, info.name, !tvCards.isEmpty() && tvCards.get(0) == this));
            FireTv tv = cfg.tv(info.id);
            if (!info.stick.isEmpty()) status.setText("…");
            io.execute(() -> {
                try {
                    tv.togglePower(tv.state());
                } catch (IOException e) {
                    ui(() -> toast(info.stick.isEmpty() ? e.getMessage() : adbError(e)));
                }
                if (info.stick.isEmpty()) return; // no stick: nothing tells on from off
                State s = tv.tvState(tv.state());
                ui(() -> {
                    show(s);
                    main.postDelayed(MainActivity.this::refresh, 4000); // the TV takes a few seconds to switch
                    main.postDelayed(MainActivity.this::refresh, 10000);
                });
            });
        }

        /** The stick's state (and the TV's through it); the first TV with a stick also tells if we're home. */
        void check(int gen, boolean main) {
            if (info.stick.isEmpty()) return;
            FireTv tv = cfg.tv(info.id);
            tv.onKeyPrompt(() -> ui(() -> {
                Ui.reachable(tile, true);
                status.setText("On the TV: tick \"Always allow\", then Allow");
                status.setTextColor(Ui.RED);
            }));
            io.execute(() -> {
                try {
                    State stick = tv.checkedState();
                    State s = tv.tvState(stick);
                    ui(() -> {
                        if (gen != generation) return;
                        show(s);
                        if (main) {
                            genStick = stick;
                            afterChecks();
                        }
                    });
                } catch (IOException e) {
                    // A key prompt means the stick answered: we're home.
                    State stick = e instanceof Adb.AdbAuthException ? State.ON : State.UNREACHABLE;
                    ui(() -> {
                        if (gen != generation) return;
                        show(State.UNREACHABLE);
                        status.setText(adbError(e));
                        if (main) {
                            genStick = stick;
                            afterChecks();
                        }
                    });
                }
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
            if (!bar.isPressed()) bar.setProgress(brightness, true);
            updating = false;
            Ui.reachable(view, reachable);
            cfg.prefs.edit().putString("light_" + name, (on ? "1," : "0,") + brightness).apply();
        }

        void send(boolean on, Integer brightness) {
            Ui.haptic(view);
            byHand(sc -> Scenes.usesLight(sc, name));
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
            byHand(sc -> Scenes.usesLight(sc, name));
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
                    if (!on) byHand(sc -> Scenes.leavesOn(sc, name)); // switched off elsewhere: not the scene any more
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

    // --- music (Spotify) ---

    private TextView nowPlaying, speakersButton;
    private List<Spotify.Device> lastDevices = new ArrayList<>();

    /** What's playing, the controls, and (top right) where it plays: tap it to move the music. */
    private View musicTile() {
        LinearLayout tile = Ui.tile(this);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Music", 18, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        speakersButton = Ui.button(this, "🔊 Speakers", false);
        speakersButton.setTextSize(13);
        speakersButton.setSingleLine(true);
        speakersButton.setMaxWidth(Ui.dp(this, 200));
        speakersButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        speakersButton.setContentDescription("Speakers: choose where the music plays");
        speakersButton.setOnClickListener(v -> showSpeakers());
        head.addView(speakersButton);
        tile.addView(head);
        nowPlaying = Ui.text(this, "Checking Spotify…", 13, Ui.DIM, false);
        nowPlaying.setPadding(0, Ui.dp(this, 6), 0, 0);
        tile.addView(nowPlaying);

        // ⏮ ⏯ ⏭, then the playing speaker's volume; shown once something is playing.
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        TextView prev = Ui.button(this, "⏮", false), next = Ui.button(this, "⏭", false);
        playPause = Ui.button(this, "▶", true);
        prev.setContentDescription("Previous");
        next.setContentDescription("Next");
        prev.setOnClickListener(v -> control(v, Spotify::previous));
        next.setOnClickListener(v -> control(v, Spotify::next));
        playPause.setOnClickListener(v -> control(v, playingNow ? Spotify::pause : Spotify::play));
        TextView[] keys = {prev, playPause, next};
        for (int k = 0; k < keys.length; k++) {
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, -2, 1);
            if (k > 0) kl.leftMargin = Ui.dp(this, 8);
            row.addView(keys[k], kl);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.dp(this, 12);
        controls.addView(row, rl);
        volume = new android.widget.SeekBar(this);
        volume.setMax(100);
        volume.setContentDescription("Volume");
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
        volume.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
        volume.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {
                int level = b.getProgress();
                control(b, sp -> sp.volume(level));
            }
        });
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        vl.topMargin = Ui.dp(this, 8);
        controls.addView(volume, vl);
        tile.addView(controls);
        return tile;
    }

    private LinearLayout controls;
    private TextView playPause;
    private android.widget.SeekBar volume;
    private boolean playingNow;

    private interface MusicCall {
        void run(Spotify sp) throws IOException;
    }

    /** A playback control, then the card checks again (Spotify takes a moment to change). */
    private void control(View v, MusicCall call) {
        Spotify sp = cfg.spotify();
        if (sp == null) return;
        byHand(Scenes::usesMusic);
        Ui.haptic(v);
        io.execute(() -> {
            try {
                call.run(sp);
            } catch (IOException e) {
                ui(() -> toast(e.getMessage()));
            }
            ui(() -> main.postDelayed(this::loadMusic, 700));
        });
    }

    /** Your own name for a speaker, shown here and in scenes; Spotify keeps its own. */
    private void renameSpeaker(String spotifyName) {
        String names = cfg.get(Config.SPEAKER_NAMES);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(Spotify.label(names, spotifyName));
        e.setHint(spotifyName);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setContentDescription("Name for " + spotifyName);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(e);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename " + spotifyName)
            .setMessage("Empty = Spotify's name. Scenes keep working.")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                cfg.put(Config.SPEAKER_NAMES, Spotify.rename(cfg.get(Config.SPEAKER_NAMES), spotifyName, e.getText().toString()));
                loadMusic();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadMusic() {
        Spotify sp = cfg.spotify();
        if (sp == null || nowPlaying == null) return;
        io.execute(() -> {
            try {
                List<Spotify.Device> ds = sp.devices();
                Spotify.Playing p = sp.playing();
                ui(() -> paintMusic(ds, p));
            } catch (IOException e) {
                ui(() -> Ui.setText(nowPlaying, e.getMessage()));
            }
        });
    }

    private void paintMusic(List<Spotify.Device> ds, Spotify.Playing p) {
        String names = cfg.get(Config.SPEAKER_NAMES);
        Ui.setText(nowPlaying, p == null ? "Nothing playing · tap a speaker to start"
            : (p.playing ? "▶ " : "Paused · ") + p.title + (p.by.isEmpty() ? "" : " — " + p.by)
                + (p.device.isEmpty() ? "" : " · on " + Spotify.label(names, p.device)));
        controls.setVisibility(p == null ? View.GONE : View.VISIBLE);
        playingNow = p != null && p.playing;
        playPause.setText(playingNow ? "❚❚" : "▶");
        playPause.setContentDescription(playingNow ? "Pause" : "Play");
        volume.setVisibility(p != null && p.volume >= 0 ? View.VISIBLE : View.GONE);
        if (p != null && p.volume >= 0 && !volume.isPressed()) volume.setProgress(p.volume);
        lastDevices = ds;
        Spotify.Device active = null;
        StringBuilder seen = new StringBuilder();
        for (Spotify.Device d : ds) {
            seen.append(d.name).append('\n');
            if (d.active) active = d;
        }
        if (!ds.isEmpty()) cfg.put(Config.SPOTIFY_SPEAKERS, seen.toString().trim()); // for the scene editor
        speakersButton.setText("🔊 " + (active == null ? "Speakers" : Spotify.label(names, active.name)));
        Ui.style(speakersButton, active != null);
    }

    /** A place the music can go: a Spotify speaker, or this phone (its speaker or what's connected to it). */
    private static final class Target {
        final String key, label; // key: the name renames are kept under
        final Spotify.Device device; // null = this phone
        final boolean active;

        Target(String key, String label, Spotify.Device device, boolean active) {
            this.key = key;
            this.label = label;
            this.device = device;
            this.active = active;
        }
    }

    private String phoneName() {
        String n = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        return n == null || n.trim().isEmpty() ? android.os.Build.MODEL : n.trim();
    }

    /** Bluetooth / wired speakers and headphones connected to the phone, by name. */
    private List<String> phoneOutputs() {
        List<String> out = new ArrayList<>();
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            boolean wired = t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
            boolean bt = t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                || t == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER;
            if (!wired && !bt) continue;
            CharSequence pn = d.getProductName();
            String name = pn == null || pn.toString().trim().isEmpty() || wired && pn.toString().equals(android.os.Build.MODEL)
                ? (wired ? "Headphones" : "Bluetooth audio") : pn.toString().trim();
            if (!out.contains(name)) out.add(name);
        }
        return out;
    }

    /** The speakers: tap = the music goes there, hold = rename. */
    private void showSpeakers() {
        Ui.haptic(speakersButton);
        String names = cfg.get(Config.SPEAKER_NAMES), me = phoneName();
        Spotify.Device phone = Spotify.phone(lastDevices, me);
        List<Target> targets = new ArrayList<>();
        for (Spotify.Device d : lastDevices) {
            if (d != phone) targets.add(new Target(d.name, Spotify.label(names, d.name), d, d.active));
        }
        boolean phoneActive = phone != null && phone.active;
        List<String> outs = phoneOutputs();
        // With something connected, Android plays the phone's music there, not on its own speaker.
        targets.add(new Target(me, Spotify.label(names, me) + (outs.isEmpty() ? " (this phone)" : " (phone speaker)"),
            null, phoneActive && outs.isEmpty()));
        for (String o : outs) targets.add(new Target(o, Spotify.label(names, o) + " · on the phone", null, phoneActive));
        String[] labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (targets.get(i).active ? "● " : "   ") + targets.get(i).label;
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Play on")
            .setItems(labels, (d, w) -> moveMusic(targets.get(w)))
            .setNegativeButton("Close", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            dialog.dismiss();
            renameSpeaker(targets.get(pos).key);
            return true;
        }));
        dialog.show();
        if (lastDevices.isEmpty()) toast("Speakers show up here once Spotify has played on them");
    }

    private void moveMusic(Target t) {
        Spotify sp = cfg.spotify();
        if (sp == null) return;
        byHand(Scenes::usesMusic);
        speakersButton.setText("🔊 Moving…");
        String me = phoneName();
        io.execute(() -> {
            try {
                Spotify.Device d = t.device;
                if (d == null) {
                    d = Spotify.phone(sp.devices(), me);
                    if (d == null) {
                        // Spotify on the phone is closed, so Spotify doesn't list it: open it, wait for it.
                        Intent open = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                        if (open == null) throw new IOException("Install Spotify on the phone first");
                        ui(() -> startActivity(open));
                        for (int i = 0; i < 10 && d == null; i++) {
                            Thread.sleep(1000);
                            d = Spotify.phone(sp.devices(), me);
                        }
                        if (d == null) throw new IOException("Spotify on the phone didn't show up: try again");
                    }
                }
                sp.transfer(d.id);
            } catch (IOException e) {
                ui(() -> toast(e.getMessage()));
            } catch (InterruptedException e) {
                return;
            }
            ui(() -> main.postDelayed(this::loadMusic, 1500)); // Spotify takes a moment to switch
        });
    }

    // --- scenes ---

    private final Map<String, TextView> sceneButtons = new java.util.HashMap<>();

    private java.util.Set<String> activeScenes() {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (String n : cfg.get(Config.SCENES_ACTIVE).split("\n")) if (!n.trim().isEmpty()) s.add(n.trim());
        return s;
    }

    /** The active scene, or null. */
    private Scenes.Scene activeScene() {
        java.util.Set<String> act = activeScenes();
        if (act.isEmpty()) return null;
        try {
            for (Scenes.Scene sc : Scenes.parse(cfg.get(Config.SCENES))) if (act.contains(sc.name)) return sc;
        } catch (ParseException e) {
            // Unreadable scenes: none.
        }
        return null;
    }

    /** Something the active scene controls changed by hand: it's no longer what's on (nothing else is done). */
    private void byHand(java.util.function.Predicate<Scenes.Scene> uses) {
        Scenes.Scene a = activeScene();
        if (a != null && uses.test(a)) saveActive(new java.util.LinkedHashSet<>());
    }

    private void saveActive(java.util.Set<String> s) {
        cfg.put(Config.SCENES_ACTIVE, String.join("\n", s));
        for (Map.Entry<String, TextView> e : sceneButtons.entrySet()) Ui.style(e.getValue(), s.contains(e.getKey()));
    }

    private View scenes() {
        sceneButtons.clear();
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
            boolean toggle = !Scenes.undo(scene).steps.isEmpty();
            TextView b = Ui.button(this, scene.name, toggle && activeScenes().contains(scene.name));
            sceneButtons.put(scene.name, b);
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
        // A scene that switches things on is a toggle: tapped while active, it switches them off instead.
        // One that only switches things off (All off) is a plain button and clears every active scene.
        // One active scene at a time: another one active means a smooth swap (fades, nothing off-and-on).
        Scenes.Scene off = Scenes.undo(scene), before = null;
        boolean toggle = !off.steps.isEmpty(), turningOff = toggle && activeScenes().contains(scene.name);
        if (toggle && !turningOff) {
            try {
                for (Scenes.Scene o : Scenes.parse(cfg.get(Config.SCENES))) {
                    if (!o.name.equals(scene.name) && activeScenes().contains(o.name)) before = o;
                }
            } catch (ParseException e) {
                // Unreadable scenes: nothing to swap from.
            }
        }
        Scenes.Scene todo = turningOff ? off : before != null ? Scenes.swap(before, scene, cfg.lights().keySet()) : scene;
        b.setText(turningOff ? "Turning off…" : before != null ? "Switching…" : "Running…");
        android.animation.ValueAnimator shimmer = Ui.shimmer(b);
        String server = cfg.get(Config.SERVER), pub = cfg.get(Config.SERVER_PUBLIC);
        SceneRunner runner = new SceneRunner(cfg.lights(), cfg.tv(), cfg.switchBot(), cfg.get(Config.SB_BOT),
            () -> Server.effective(Server.status(server, pub), Metrics.fetch(metricsUrl(server)) != null),
            cfg.jellyfin(), this::askWhat, () -> ui(() -> startActivity(new Intent(this, RemoteActivity.class))))
            .music(cfg.spotify())
            .tvs(cfg::tvNamed)
            .smooth(turningOff || before != null)
            .signal(turningOff ? "off" : before != null ? "swap" : "start", before == null ? null : before.name);
        io.execute(() -> {
            int[] r = runner.run(todo);
            ui(() -> {
                shimmer.cancel();
                Ui.pop(b);
                java.util.Set<String> act = new java.util.LinkedHashSet<>();
                if (toggle && !turningOff) act.add(scene.name); // the only active one now
                saveActive(act);
                b.setText(r[0] == r[1] ? (turningOff ? "Off" : "Done") : r[0] + "/" + r[1] + " done");
                main.postDelayed(() -> { b.setText(scene.name); b.setEnabled(true); }, 3000);
                refresh();
                main.postDelayed(this::loadMusic, 1500); // Spotify takes a moment to switch speakers
                main.postDelayed(this::refresh, 4000); // and the TV a few seconds to turn on or off
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
        TvCard mainTv = null; // the first TV with a stick: its answer also says whether we're home
        for (TvCard c : tvCards) if (mainTv == null && !c.info.stick.isEmpty()) mainTv = c;
        for (TvCard c : tvCards) c.check(gen, c == mainTv);
        if (mainTv == null) genStick = State.ON; // no stick to look for
        for (LightRow r : rows.values()) io.execute(r::load);
        io.execute(this::findLights);
        loadMusic();
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
                if (me != null && cfg.found(Discovery.find(me, cfg.adbKey()))) {
                    ui(this::refresh);
                } else {
                    ui(() -> maybeVpn(server, stick));
                }
            });
            return;
        }
        maybeVpn(genServer, genStick);
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
}
