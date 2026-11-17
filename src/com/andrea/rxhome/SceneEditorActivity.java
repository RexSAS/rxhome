package com.andrea.rxhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Builds a scene by tapping, no text: a name, then steps (lights, TV, apps, Claude FM, server button,
 * wait), each with its own controls. Saves to the same scene format (Scenes.format), so scenes stay
 * compatible with everything else. Extra "index": which scene to edit, -1 for a new one.
 */
public class SceneEditorActivity extends Activity {

    private Config cfg;
    private int index;
    private List<Scenes.Scene> scenes;
    private final List<String[]> steps = new ArrayList<>();
    private EditText name;
    private LinearLayout stepsBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        index = getIntent().getIntExtra("index", -1);
        try {
            scenes = new ArrayList<>(Scenes.parse(cfg.get(Config.SCENES)));
        } catch (ParseException e) {
            scenes = new ArrayList<>();
        }
        if (index >= scenes.size()) index = -1;
        Scenes.Scene sc = index >= 0 ? scenes.get(index) : null;
        if (sc != null) for (String[] s : sc.steps) steps.add(s.clone());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, sc == null ? "New scene" : "Scene", 26, Ui.TEXT, true));

        label(col, "NAME");
        name = new EditText(this);
        name.setText(sc == null ? "" : sc.name);
        name.setHint("e.g. Movie night");
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.DIM);
        name.setTypeface(Ui.font(this, true));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setContentDescription("Scene name");
        col.addView(name);

        label(col, "STEPS · RUN IN ORDER");
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(stepsBox);

        TextView add = Ui.button(this, "+ ADD STEP", false);
        add.setOnClickListener(v -> addStep());
        col.addView(add);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, Ui.dp(this, 24), 0, 0);
        if (sc != null) {
            TextView del = Ui.button(this, "DELETE SCENE", false);
            del.setOnClickListener(v -> confirmDelete());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1);
            dl.rightMargin = Ui.dp(this, 8);
            actions.addView(del, dl);
        }
        TextView save = Ui.button(this, "SAVE", true);
        save.setOnClickListener(v -> save());
        actions.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(actions);

        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
        render();
    }

    private void label(LinearLayout col, String text) {
        TextView t = Ui.text(this, text, 12, Ui.RED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    /** Draws every step with its controls. */
    private void render() {
        stepsBox.removeAllViews();
        if (steps.isEmpty()) {
            TextView none = Ui.text(this, "No steps yet: add the first one below.", 13, Ui.DIM, false);
            none.setPadding(0, 0, 0, Ui.dp(this, 12));
            stepsBox.addView(none);
        }
        for (int i = 0; i < steps.size(); i++) stepsBox.addView(stepView(i));
    }

    private View stepView(int i) {
        String[] s = steps.get(i);
        LinearLayout tile = Ui.tile(this);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, (i + 1) + ". " + Scenes.describe(s), 15, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(small("↑", "Move up", v -> move(i, -1)));
        head.addView(small("↓", "Move down", v -> move(i, 1)));
        head.addView(small("✕", "Remove step", v -> { steps.remove(i); render(); }));
        tile.addView(head);

        switch (s[0]) {
            case "light":
                lightControls(tile, i, title);
                break;
            case "tv":
                if (s[1].equals("app")) {
                    Map<String, String> apps = Scenes.apps();
                    chips(tile, new ArrayList<>(apps.values()), apps.get(s[2]), pick -> {
                        for (Map.Entry<String, String> e : apps.entrySet()) if (e.getValue().equals(pick)) s[2] = e.getKey();
                    });
                } else if (!s[1].equals("music")) {
                    chips(tile, Arrays.asList("On", "Off"), s[1].equals("on") ? "On" : "Off", pick -> s[1] = pick.toLowerCase());
                }
                break;
            case "wait":
                slider(tile, 1, 60, Integer.parseInt(s[1]), v -> {
                    s[1] = Integer.toString(v);
                    title.setText((i + 1) + ". " + Scenes.describe(s));
                });
                break;
            default:
                break; // server press, Claude FM: nothing to set
        }
        return tile;
    }

    /** Which light, on/off, brightness (optional), white (optional). */
    private void lightControls(LinearLayout tile, int i, TextView title) {
        List<String> who = new ArrayList<>();
        who.add("All");
        who.addAll(cfg.lights().keySet());
        String[] s = steps.get(i);
        if (!s[1].equals("all") && !who.contains(s[1])) who.add(s[1]); // a light no longer found: keep it visible
        chips(tile, who, s[1].equals("all") ? "All" : s[1], pick -> steps.set(i, light(steps.get(i), pick.equals("All") ? "all" : pick, null, null, null)));
        chips(tile, Arrays.asList("On", "Off"), s[2].equals("on") ? "On" : "Off",
            pick -> steps.set(i, light(steps.get(i), null, pick.toLowerCase(), null, null)));
        if (!s[2].equals("on")) return;

        boolean dims = s.length >= 4;
        chips(tile, Arrays.asList("Keep brightness", "Set brightness"), dims ? "Set brightness" : "Keep brightness",
            pick -> steps.set(i, light(steps.get(i), null, null, pick.startsWith("Set") ? "50" : "", null)));
        if (dims) {
            slider(tile, 1, 100, Integer.parseInt(s[3]), v -> {
                steps.set(i, light(steps.get(i), null, null, Integer.toString(v), null));
                title.setText((i + 1) + ". " + Scenes.describe(steps.get(i)));
            });
            String white = s.length == 5 ? (s[4].equals("warm") ? "Warm white" : s[4].equals("cool") ? "Cool white" : "Colour as is") : "Colour as is";
            chips(tile, Arrays.asList("Colour as is", "Warm white", "Cool white"), white,
                pick -> steps.set(i, light(steps.get(i), null, null, null, pick.startsWith("Warm") ? "warm" : pick.startsWith("Cool") ? "cool" : "")));
        }
    }

    /** A light step with some parts changed (null = keep, "" = drop). Keeps the format's order rules. */
    private static String[] light(String[] s, String who, String onOff, String brightness, String white) {
        String w = who != null ? who : s[1], o = onOff != null ? onOff : s[2];
        String b = brightness != null ? brightness : (s.length >= 4 ? s[3] : "");
        String c = white != null ? white : (s.length == 5 ? s[4] : "");
        List<String> out = new ArrayList<>(Arrays.asList("light", w, o));
        if (o.equals("on") && !b.isEmpty()) {
            out.add(b);
            if (!c.isEmpty()) out.add(c);
        }
        return out.toArray(new String[0]);
    }

    private interface Pick {
        void picked(String choice);
    }

    /** A row of chips, one selected; picking one updates the step and redraws. */
    private void chips(LinearLayout tile, List<String> choices, String selected, Pick pick) {
        LinearLayout row = new LinearLayout(this);
        for (String c : choices) {
            TextView chip = Ui.button(this, c, c.equals(selected));
            chip.setTextSize(13);
            chip.setContentDescription(c + (c.equals(selected) ? ", selected" : ""));
            chip.setOnClickListener(v -> {
                Ui.haptic(v);
                pick.picked(c);
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            row.addView(chip, lp);
        }
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        tile.addView(sc, lp);
    }

    private interface Slide {
        void moved(int value);
    }

    private void slider(LinearLayout tile, int min, int max, int value, Slide slide) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, value - min));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) slide.moved(v + min); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        lp.topMargin = Ui.dp(this, 8);
        tile.addView(bar, lp);
    }

    private TextView small(String text, String description, View.OnClickListener l) {
        TextView b = Ui.text(this, text, 18, Ui.TEXT, true);
        int p = Ui.dp(this, 10);
        b.setPadding(p, p / 2, p, p / 2);
        b.setContentDescription(description);
        b.setOnClickListener(v -> { Ui.haptic(v); l.onClick(v); });
        return b;
    }

    private void move(int i, int by) {
        int j = i + by;
        if (j < 0 || j >= steps.size()) return;
        steps.add(j, steps.remove(i));
        render();
    }

    private void addStep() {
        String[] names = {"Lights", "TV on / off", "Open an app on the TV", "Claude FM on the TV", "Press the server's power button", "Wait"};
        String[][] start = {{"light", "all", "on"}, {"tv", "on"}, {"tv", "app", "org.rxfilms.tv"}, {"tv", "music"},
            {"server", "press"}, {"wait", "5"}};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add a step")
            .setItems(names, (d, w) -> {
                steps.add(start[w].clone());
                render();
            })
            .show();
    }

    private void save() {
        Scenes.Scene sc = new Scenes.Scene(Scenes.cleanName(name.getText().toString()), new ArrayList<>(steps));
        if (index >= 0) scenes.set(index, sc);
        else scenes.add(sc);
        store("Saved " + sc.name);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete " + scenes.get(index).name + "?")
            .setPositiveButton("Delete", (d, w) -> {
                String gone = scenes.remove(index).name;
                store("Deleted " + gone);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void store(String done) {
        String text = Scenes.format(scenes);
        try {
            Scenes.parse(text); // never save something the scene runner can't read
        } catch (ParseException e) {
            Toast.makeText(this, "Can't save: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        cfg.put(Config.SCENES, text);
        Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
        finish();
    }
}
