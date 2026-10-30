package dev.wasmcart.androidplayer;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Process;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

/**
 * Fullscreen player for one cart, in its own process (":player"). LibraryActivity
 * has already copied the cart to a real file, since the native host's zip reader
 * needs a path. The extras become native's argv: cart path, save file, code cache
 * directory, the size to offer the cart, controls settings file.
 *
 * A small button over the game opens the on-screen controls' settings. The cart
 * pauses while they are open, and the controls can be dragged around the game.
 */
public class PlayerActivity extends SDLActivity {
    static final String EXTRA_CART = "cart";
    static final String EXTRA_SAVE = "save";
    static final String EXTRA_CODE_CACHE = "code_cache";
    static final String EXTRA_DECLARES_SIZE = "declares_size";
    static final String EXTRA_CONTROLS = "controls";

    // native/overlay.h
    private static final int OV_EDIT = 0, OV_SIZE = 1, OV_OPACITY = 2, OV_GROUPS = 3, OV_FLOATING = 4,
            OV_HAPTICS = 5, OV_SHOW = 6, OV_RESET = 7;
    private static final int OV_GROUP_STICKS = 1, OV_GROUP_CLICKS = 2, OV_GROUP_SHOULDERS = 4, OV_GROUP_TRIGGERS = 8;

    private static native void nativeOverlayCommand(int key, int value);
    private static native int nativeOverlayGet(int key);

    private Button gear;
    private View panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        super.onCreate(savedInstanceState);
        if (mLayout != null) addGear();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected String[] getArguments() {
        Intent intent = getIntent();
        return new String[] {
            intent.getStringExtra(EXTRA_CART),
            intent.getStringExtra(EXTRA_SAVE),
            intent.getStringExtra(EXTRA_CODE_CACHE),
            intent.getBooleanExtra(EXTRA_DECLARES_SIZE, false) ? "cart-size" : "screen-size",
            intent.getStringExtra(EXTRA_CONTROLS),
        };
    }

    /**
     * Called from native. True only for a device Android says is not a gamepad.
     * A name that matches nothing counts as a gamepad: SDL rewrites the names of
     * pads it knows, and a real pad must never be turned away.
     */
    public boolean isNotGamepad(String name) {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !name.equals(device.getName())) continue;
            int sources = device.getSources();
            return (sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                    && (sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK;
        }
        return false;
    }

    /** Called from native: the on-screen controls are showing, or a gamepad took over. */
    public void onOverlayVisible(boolean visible) {
        runOnUiThread(() -> {
            if (gear != null && panel == null) gear.setVisibility(visible ? View.VISIBLE : View.GONE);
        });
    }

    // ─── Controls settings ──────────────────────────────────────────────────

    /** The player's own theme is a bare fullscreen one; the panel's widgets want a real one. */
    private ContextThemeWrapper panelTheme() {
        return new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void addGear() {
        gear = new Button(this);
        gear.setText("Controls");
        gear.setTextSize(11);
        gear.setAllCaps(false);
        gear.setTextColor(Color.argb(170, 255, 255, 255));
        gear.setBackground(new ColorDrawable(Color.argb(60, 0, 0, 0)));
        gear.setMinWidth(0);
        gear.setMinimumWidth(0);
        gear.setMinHeight(0);
        gear.setMinimumHeight(0);
        gear.setPadding(dp(12), dp(4), dp(12), dp(4));
        gear.setVisibility(View.GONE);
        gear.setOnClickListener(v -> openPanel());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wrap(gear, lp);
    }

    /** mLayout is SDL's own layout type; a full-window frame takes our gravity params. */
    private FrameLayout overlayRoot;

    private void wrap(View view, FrameLayout.LayoutParams lp) {
        if (overlayRoot == null) {
            overlayRoot = new FrameLayout(this) {
                // Only our own views take touches; the rest reach the game beneath
                @Override
                public boolean onTouchEvent(android.view.MotionEvent event) {
                    return false;
                }
            };
            mLayout.addView(overlayRoot, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        overlayRoot.addView(view, lp);
    }

    private void openPanel() {
        nativeOverlayCommand(OV_EDIT, 1);
        gear.setVisibility(View.GONE);

        LinearLayout column = new LinearLayout(panelTheme());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(14), dp(10), dp(14), dp(10));

        column.addView(heading("Drag a control to move it."));

        column.addView(toggle("Show on-screen controls", OV_SHOW));
        groups = nativeOverlayGet(OV_GROUPS);
        column.addView(group("Sticks", OV_GROUP_STICKS));
        column.addView(group("Stick clicks", OV_GROUP_CLICKS));
        column.addView(group("Shoulders", OV_GROUP_SHOULDERS));
        column.addView(group("Triggers", OV_GROUP_TRIGGERS));

        column.addView(slider("Size", OV_SIZE, 50, 200));
        column.addView(slider("Opacity", OV_OPACITY, 5, 100));

        column.addView(toggle("Sticks center where you touch", OV_FLOATING));
        column.addView(toggle("Vibrate on press", OV_HAPTICS));

        LinearLayout actions = new LinearLayout(this);
        actions.addView(action("Reset", v -> {
            nativeOverlayCommand(OV_RESET, 1);
            closePanel();
            openPanel();
        }), new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(action("Done", v -> closePanel()), new LinearLayout.LayoutParams(0, dp(38), 1));
        column.addView(actions);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new ColorDrawable(Color.argb(225, 16, 20, 26)));
        scroll.addView(column);
        panel = scroll;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wrap(panel, lp);
    }

    private void closePanel() {
        nativeOverlayCommand(OV_EDIT, 0);
        if (panel != null) overlayRoot.removeView(panel);
        panel = null;
        gear.setVisibility(View.VISIBLE);
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.rgb(200, 205, 215));
        return t;
    }

    private int groups;

    private Switch group(String label, int bit) {
        Switch s = new Switch(panelTheme());
        s.setText(label);
        s.setTextSize(12);
        s.setTextColor(Color.rgb(200, 205, 215));
        s.setChecked((groups & bit) != 0);
        s.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            groups = on ? groups | bit : groups & ~bit;
            nativeOverlayCommand(OV_GROUPS, groups);
        });
        return s;
    }

    private Button action(String label, View.OnClickListener listener) {
        Button b = new Button(panelTheme());
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(listener);
        return b;
    }

    private View slider(String label, int key, int min, int max) {
        TextView name = heading(label);
        SeekBar bar = new SeekBar(panelTheme());
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(nativeOverlayGet(key));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser) nativeOverlayCommand(key, value);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        row.addView(name);
        row.addView(bar);
        return row;
    }

    private Switch toggle(String label, int key) {
        Switch s = new Switch(panelTheme());
        s.setText(label);
        s.setTextSize(12);
        s.setTextColor(Color.rgb(200, 205, 215));
        s.setChecked(nativeOverlayGet(key) != 0);
        s.setOnCheckedChangeListener((CompoundButton b, boolean on) -> nativeOverlayCommand(key, on ? 1 : 0));
        return s;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The next cart gets a fresh process: V8 cannot be started again in
        // this one. SDL has told native to quit by now, and native saves first.
        Process.killProcess(Process.myPid());
    }
}
