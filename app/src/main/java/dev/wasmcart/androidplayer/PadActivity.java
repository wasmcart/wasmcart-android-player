package dev.wasmcart.androidplayer;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A screen driven by a gamepad as well as by touch. The bottom and right face
 * buttons select and go back, in whichever order the player chose, and the
 * footer shows which is which. Face buttons are never named, only shown.
 */
abstract class PadActivity extends Activity {
    private TextView selectHint, backHint;

    @Override
    protected void onResume() {
        super.onResume();
        showHints();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return super.dispatchKeyEvent(e);
        boolean select = (code == KeyEvent.KEYCODE_BUTTON_A) != Settings.confirmRight(this);
        return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                select ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_BACK, e.getRepeatCount(),
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource()));
    }

    /** The select and back hints, for the bottom of the screen. */
    LinearLayout hints() {
        selectHint = hint("Select");
        backHint = hint("Back");
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(6), 0, dp(6));
        footer.addView(selectHint);
        footer.addView(backHint);
        showHints();
        return footer;
    }

    void showHints() {
        if (selectHint == null) return;
        boolean right = Settings.confirmRight(this);
        selectHint.setCompoundDrawables(icon(right ? ButtonIcon.RIGHT : ButtonIcon.BOTTOM), null, null, null);
        backHint.setCompoundDrawables(icon(right ? ButtonIcon.BOTTOM : ButtonIcon.RIGHT), null, null, null);
    }

    ButtonIcon icon(int filled) {
        ButtonIcon icon = new ButtonIcon(filled, Color.rgb(200, 205, 215));
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
