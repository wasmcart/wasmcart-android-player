package dev.wasmcart.androidplayer;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings for every cart: which face button launches, the resolution offered to
 * carts, the frame rate readout. Each row changes when chosen, so a gamepad
 * needs nothing but up, down and the bottom button. On/off rows are switches.
 */
public class SettingsActivity extends PadActivity {
    private Switch launchValue, fpsValue;
    private TextView resolutionValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);

        launchValue = toggle();
        resolutionValue = value();
        fpsValue = toggle();

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        View first = row(rows, "Launch with the right button",
                "The right face button launches and the bottom one goes back, as on Nintendo consoles. Games see every button as it is.",
                launchValue, v -> {
                    Settings.setLaunchRight(this, !Settings.launchRight(this));
                    refresh();
                    flashHints();
                });
        row(rows, "Resolution", "The tallest picture a cart draws. Lower runs faster on a weaker device and still fills the screen.",
                resolutionValue, v -> {
                    int at = 0;
                    int current = Settings.maxHeight(this);
                    for (int i = 0; i < Settings.MAX_HEIGHTS.length; i++) if (Settings.MAX_HEIGHTS[i] == current) at = i;
                    Settings.setMaxHeight(this, Settings.MAX_HEIGHTS[(at + 1) % Settings.MAX_HEIGHTS.length]);
                    refresh();
                });
        row(rows, "Show FPS", "Frames a second in the corner while a cart runs.",
                fpsValue, v -> {
                    Settings.setShowFps(this, !Settings.showFps(this));
                    refresh();
                });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(title);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(hints());

        int pad = dp(16);
        root.setPadding(pad, pad, pad, 0);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        refresh();
        first.requestFocus();
    }

    private void refresh() {
        launchValue.setChecked(Settings.launchRight(this));
        resolutionValue.setText(Settings.resolutionLabel(Settings.maxHeight(this)));
        fpsValue.setChecked(Settings.showFps(this));
        showHints();
    }

    private View row(LinearLayout parent, String name, String about, View value, View.OnClickListener onClick) {
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(18);
        label.setTextColor(Color.WHITE);
        TextView detail = new TextView(this);
        detail.setText(about);
        detail.setTextSize(13);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label);
        text.addView(detail);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(14));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value);
        row.setFocusable(true);
        row.setClickable(true);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(onClick);
        parent.addView(row);
        return row;
    }

    /** Shows the row's state; the row takes the touch or press and flips it. */
    private Switch toggle() {
        Switch s = new Switch(this);
        s.setFocusable(false);
        s.setClickable(false);
        s.setPadding(dp(16), 0, 0, 0);
        return s;
    }

    private TextView value() {
        TextView t = new TextView(this);
        t.setTextSize(18);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(16), 0, 0, 0);
        return t;
    }

    @Override
    String chooseLabel() {
        return "Change";
    }
}
