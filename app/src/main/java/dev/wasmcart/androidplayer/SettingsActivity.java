package dev.wasmcart.androidplayer;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings for every cart: swapped face buttons, the resolution offered to
 * carts, the frame rate readout. Each row changes when chosen, so a gamepad
 * needs nothing but up, down and select.
 */
public class SettingsActivity extends PadActivity {
    private TextView swapValue, resolutionValue, fpsValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);

        swapValue = value();
        resolutionValue = value();
        fpsValue = value();

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        View first = row(rows, "Swap face buttons",
                "Turn on if the bottom button acts as the right one, as on Nintendo-style pads. Fixes menus and games.",
                swapValue, v -> {
                    Settings.setSwapFace(this, !Settings.swapFace(this));
                    refresh();
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
        swapValue.setText(Settings.swapFace(this) ? "On" : "Off");
        resolutionValue.setText(Settings.resolutionLabel(Settings.maxHeight(this)));
        fpsValue.setText(Settings.showFps(this) ? "On" : "Off");
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
