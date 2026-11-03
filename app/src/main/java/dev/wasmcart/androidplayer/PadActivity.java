package dev.wasmcart.androidplayer;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A screen driven by a gamepad as well as by touch. The bottom face button
 * chooses and the right one goes back, and the footer shows which is which.
 * Face buttons are never named, only shown.
 */
abstract class PadActivity extends Activity {
    /** What the bottom button does on this screen, for its hint. */
    abstract String chooseLabel();

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return super.dispatchKeyEvent(e);
        boolean bottom = (code == KeyEvent.KEYCODE_BUTTON_A) != Settings.swapFace(this);
        return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                bottom ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_BACK, e.getRepeatCount(),
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource()));
    }

    /** The hints for the bottom of the screen. */
    LinearLayout hints() {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(6), 0, dp(6));
        footer.addView(hint(ButtonIcon.BOTTOM, chooseLabel()));
        footer.addView(hint(ButtonIcon.RIGHT, "Back"));
        return footer;
    }

    ButtonIcon icon(int filled) {
        ButtonIcon icon = new ButtonIcon(filled, Color.rgb(200, 205, 215));
        icon.setBounds(0, 0, dp(28), dp(28));
        return icon;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView hint(int button, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setCompoundDrawables(icon(button), null, null, null);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setCompoundDrawablePadding(dp(6));
        t.setPadding(0, 0, dp(20), 0);
        return t;
    }
}
