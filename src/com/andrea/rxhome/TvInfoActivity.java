package com.andrea.rxhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * One saved TV's info: its name, and which FireStick and Sky box are plugged into it (bound here only:
 * a home can have several FireSticks). The remote's style buttons follow what's bound.
 */
public class TvInfoActivity extends Activity {

    static final String UID = "uid";

    private Config cfg;
    private String uid;
    // What to bind when saving: an add-on's uid, "" = none, null = unchanged.
    private String stickChoice, skyChoice;
    private TextView stickButton, skyButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        uid = getIntent().getStringExtra(UID);
        Tvs.Saved t = Tvs.find(cfg.saved(), uid == null ? "" : uid);
        if (t == null) {
            finish();
            return;
        }
        boolean isTv = !t.brand.equals("none");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, isTv ? "TV info" : t.hasSky() ? "Sky box" : "FireStick", 26, Ui.TEXT, true));

        label(col, "Name");
        EditText name = new EditText(this);
        name.setText(t.name);
        name.setTextColor(Ui.TEXT);
        name.setTypeface(Ui.font(this, false));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setSingleLine(true);
        name.setContentDescription("Name");
        col.addView(name);

        if (isTv) {
            label(col, "FireStick plugged into this TV");
            stickButton = Ui.button(this, current(t.hasStick(), "FireStick", t.stickIp), false);
            stickButton.setOnClickListener(v -> pick("stick"));
            col.addView(stickButton);
            label(col, "Sky box plugged into this TV");
            skyButton = Ui.button(this, current(t.hasSky(), "Sky", t.skyIp), false);
            skyButton.setOnClickListener(v -> pick("sky"));
            col.addView(skyButton);
            TextView hint = Ui.text(this, "With a FireStick or Sky box here, the remote gets buttons to switch between "
                + "the TV and them. Add new ones with \"Add a TV, FireStick or Sky\" in Settings.", 13, Ui.DIM, false);
            hint.setPadding(0, Ui.dp(this, 8), 0, 0);
            col.addView(hint);
        }

        TextView save = Ui.button(this, "SAVE", true);
        save.setOnClickListener(v -> {
            List<Tvs.Saved> list = cfg.saved();
            Tvs.Saved me = Tvs.find(list, uid);
            if (me == null) {
                finish();
                return;
            }
            String n = name.getText().toString().trim();
            if (!n.isEmpty()) me.name = n;
            apply(list, "stick", stickChoice);
            apply(list, "sky", skyChoice);
            cfg.save(list);
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 24);
        col.addView(save, lp);

        TextView remove = Ui.button(this, isTv ? "REMOVE THIS TV" : "REMOVE", false);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove " + t.name + "?")
            .setMessage(isTv && (t.hasStick() || t.hasSky())
                ? "What's plugged into it stays saved, on its own. You can add the TV again later."
                : "You can add it again later.")
            .setPositiveButton("Remove", (d, w) -> {
                List<Tvs.Saved> list = cfg.saved();
                Tvs.remove(list, uid);
                cfg.save(list);
                finish();
            })
            .setNegativeButton("Cancel", null)
            .show());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = Ui.dp(this, 8);
        col.addView(remove, rlp);

        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
    }

    private void label(LinearLayout col, String text) {
        TextView l = Ui.text(this, text, 13, Ui.DIM, false);
        l.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        col.addView(l);
    }

    private static String current(boolean has, String what, String ip) {
        return has ? what + " (" + ip + ") ▾" : "None ▾";
    }

    /** Lists None, the one bound now, and those saved on their own. */
    private void pick(String kind) {
        List<Tvs.Saved> list = cfg.saved();
        Tvs.Saved me = Tvs.find(list, uid);
        List<Tvs.Saved> free = Tvs.addOns(list, kind);
        boolean has = me != null && (kind.equals("stick") ? me.hasStick() : me.hasSky());
        String what = kind.equals("stick") ? "FireStick" : "Sky";
        int extra = has ? 2 : 1;
        String[] items = new String[free.size() + extra];
        items[0] = "None";
        if (has) items[1] = current(true, what, kind.equals("stick") ? me.stickIp : me.skyIp).replace(" ▾", " · now");
        for (int i = 0; i < free.size(); i++) {
            Tvs.Saved a = free.get(i);
            items[i + extra] = a.name + " (" + (kind.equals("stick") ? a.stickIp : a.skyIp) + ")";
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(free.isEmpty() && !has ? "No " + what + " saved yet: add one in Settings" : "Which " + what + "?")
            .setItems(items, (d, which) -> {
                String choice = which == 0 ? "" : has && which == 1 ? null : free.get(which - extra).uid;
                TextView b = kind.equals("stick") ? stickButton : skyButton;
                b.setText(which == 0 ? "None ▾" : items[which].replace(" · now", "") + " ▾");
                if (kind.equals("stick")) stickChoice = choice;
                else skyChoice = choice;
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void apply(List<Tvs.Saved> list, String kind, String choice) {
        if (choice == null) return; // unchanged
        if (choice.isEmpty()) Tvs.unbind(list, uid, kind);
        else Tvs.bind(list, uid, choice);
    }
}
