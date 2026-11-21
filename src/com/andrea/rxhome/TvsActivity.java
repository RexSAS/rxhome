package com.andrea.rxhome;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Saved TVs: rename each one and change their order (the order of the TV picker and Settings). */
public class TvsActivity extends Activity {

    private Config cfg;
    private List<Tvs.Saved> list;
    private final List<EditText> names = new ArrayList<>();
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        list = cfg.saved();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, "TVs", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Rename them, and move them up or down.", 13, Ui.DIM, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 8));
        col.addView(sub);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        col.addView(rows);
        render();

        TextView save = Ui.button(this, "SAVE", true);
        save.setOnClickListener(v -> {
            readNames();
            cfg.save(list);
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        col.addView(save, lp);

        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
    }

    /** Typed names into the list (an emptied box keeps the old name). */
    private void readNames() {
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i).getText().toString().trim();
            if (!n.isEmpty()) list.get(i).name = n;
        }
    }

    private void render() {
        rows.removeAllViews();
        names.clear();
        for (int i = 0; i < list.size(); i++) {
            Tvs.Saved t = list.get(i);
            int at = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);

            EditText name = new EditText(this);
            name.setText(t.name);
            name.setTextColor(Ui.TEXT);
            name.setTypeface(Ui.font(this, false));
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            name.setSingleLine(true);
            String brand = t.brand.startsWith("samsung") ? "Samsung" : t.brand.equals("lg") ? "LG"
                : t.brand.equals("philips") ? "Philips" : "FireStick";
            name.setHint(brand);
            name.setContentDescription("Name of the " + brand + " TV");
            names.add(name);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

            row.addView(arrow("▲", "Move " + t.name + " up", at > 0, () -> move(at, -1)));
            row.addView(arrow("▼", "Move " + t.name + " down", at < list.size() - 1, () -> move(at, 1)));
            rows.addView(row);
        }
        if (list.isEmpty()) rows.addView(Ui.text(this, "No TVs yet: add one in Settings.", 13, Ui.DIM, false));
    }

    private TextView arrow(String label, String description, boolean enabled, Runnable r) {
        TextView b = Ui.button(this, label, false);
        b.setContentDescription(description);
        b.setMinWidth(Ui.dp(this, 48));
        b.setMinHeight(Ui.dp(this, 48));
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.3f);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            r.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private void move(int i, int delta) {
        readNames(); // keep what was typed
        Tvs.move(list, i, delta);
        render();
    }
}
