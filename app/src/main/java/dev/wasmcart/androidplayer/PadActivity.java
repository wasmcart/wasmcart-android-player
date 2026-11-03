package dev.wasmcart.androidplayer;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A screen driven by a gamepad as well as by touch. The bottom face button
 * chooses and the right one goes back, or the reverse if the player chose
 * that, and the footer shows which is which. Face buttons are never named,
 * only shown.
 */
abstract class PadActivity extends Activity {
    private TextView chooseHint, backHint;

    /** What the choosing button does on this screen, for its hint. */
    abstract String chooseLabel();

    @Override
    protected void onResume() {
        super.onResume();
        showHints();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return super.dispatchKeyEvent(e);
        boolean choose = (code == KeyEvent.KEYCODE_BUTTON_A) != Settings.launchRight(this);
        return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                choose ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_BACK, e.getRepeatCount(),
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource()));
    }

    /** The hints for the bottom of the screen. */
    LinearLayout hints() {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(6), 0, dp(6));
        chooseHint = hint(chooseLabel());
        backHint = hint("Back");
        footer.addView(chooseHint);
        footer.addView(backHint);
        showHints();
        return footer;
    }

    private static final int HINT_COLOR = Color.rgb(200, 205, 215);
    private static final int FLASH_COLOR = Color.rgb(235, 64, 52);
    private static final long FLASH_MS = 700;
    private int hintColor = HINT_COLOR;

    void showHints() {
        if (chooseHint == null) return;
        boolean right = Settings.launchRight(this);
        chooseHint.setCompoundDrawables(icon(right ? ButtonIcon.RIGHT : ButtonIcon.BOTTOM), null, null, null);
        backHint.setCompoundDrawables(icon(right ? ButtonIcon.BOTTOM : ButtonIcon.RIGHT), null, null, null);
        chooseHint.setTextColor(hintColor);
        backHint.setTextColor(hintColor);
    }

    /** Shows the hints in red for a moment, so a change to them is seen. */
    void flashHints() {
        if (chooseHint == null) return;
        hintColor = FLASH_COLOR;
        showHints();
        chooseHint.removeCallbacks(endFlash);
        chooseHint.postDelayed(endFlash, FLASH_MS);
    }

    private final Runnable endFlash = () -> {
        hintColor = HINT_COLOR;
        showHints();
    };

    ButtonIcon icon(int filled) {
        ButtonIcon icon = new ButtonIcon(filled, hintColor);
        icon.setBounds(0, 0, dp(28), dp(28));
        return icon;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setCompoundDrawablePadding(dp(6));
        t.setPadding(0, 0, dp(20), 0);
        return t;
    }
}
