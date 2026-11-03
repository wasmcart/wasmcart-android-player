package dev.wasmcart.androidplayer;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings for every cart: which face button launches, the resolution offered to
 * carts, the frame rate readout. Changes are made by touch and saved at once;
 * the Back button returns to the library. On a pad the face buttons only go
 * back, with the meaning they had when the screen opened, so a press can never
 * flip a setting by mistake. On/off rows are switches.
 *
 * The launch row previews the new buttons, and the library's hints flash on
 * return when they changed.
 */
public class SettingsActivity extends PadActivity {
    private Switch launchValue, fpsValue;
    private TextView resolutionValue;
    private View firstRow;
    private boolean launchRightHere;
    private TextView previewLaunch, previewBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchRightHere = Settings.launchRight(this);

        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(24);
        title.setPadding(dp(16), 0, 0, 0);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(back);
        header.addView(title);

        launchValue = toggle();
        previewLaunch = hint("Launch");
        previewBack = hint("Back");
        LinearLayout preview = new LinearLayout(this);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(0, dp(8), 0, 0);
        preview.addView(previewLaunch);
        preview.addView(previewBack);
        resolutionValue = value();
        fpsValue = toggle();

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        firstRow = row(rows, "Launch with the right button",
                "The right face button launches and the bottom one goes back, as on Nintendo consoles. "
                        + "Games see every button as it is.",
                preview, launchValue, v -> {
                    Settings.setLaunchRight(this, !Settings.launchRight(this));
                    refresh();
                    flashPreview();
                });
        row(rows, "Resolution", "The tallest picture a cart draws. Lower runs faster on a weaker device and still fills the screen.",
                null, resolutionValue, v -> {
                    int at = 0;
                    int current = Settings.maxHeight(this);
                    for (int i = 0; i < Settings.MAX_HEIGHTS.length; i++) if (Settings.MAX_HEIGHTS[i] == current) at = i;
                    Settings.setMaxHeight(this, Settings.MAX_HEIGHTS[(at + 1) % Settings.MAX_HEIGHTS.length]);
                    refresh();
                });
        row(rows, "Show FPS", "Frames a second in the corner while a cart runs.",
                null, fpsValue, v -> {
                    Settings.setShowFps(this, !Settings.showFps(this));
                    refresh();
                });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

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
        firstRow.requestFocus();
    }

    private void refresh() {
        launchValue.setChecked(Settings.launchRight(this));
        resolutionValue.setText(Settings.resolutionLabel(Settings.maxHeight(this)));
        fpsValue.setChecked(Settings.showFps(this));
        showButtons(previewLaunch, previewBack, Settings.launchRight(this), previewColor);
    }

    private int previewColor = HINT_COLOR;

    private void flashPreview() {
        previewColor = FLASH_COLOR;
        refresh();
        previewLaunch.removeCallbacks(endPreviewFlash);
        previewLaunch.postDelayed(endPreviewFlash, FLASH_MS);
    }

    private final Runnable endPreviewFlash = () -> {
        previewColor = HINT_COLOR;
        refresh();
    };

    @Override
    boolean launchRight() {
        return launchRightHere;
    }

    private View row(LinearLayout parent, String name, String about, View below, View value,
                     View.OnClickListener onClick) {
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
        if (below != null) text.addView(below);

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
        return null;
    }

    @Override
    View firstFocus() {
        return firstRow;
    }
}
