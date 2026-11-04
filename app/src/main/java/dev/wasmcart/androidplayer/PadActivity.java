package dev.wasmcart.androidplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * A screen driven by a gamepad as well as by touch. The bottom face button
 * chooses and the right one goes back, or the reverse if the player chose
 * that, and the footer shows which is which. Face buttons are never named,
 * only shown.
 */
abstract class PadActivity extends Activity {
    private TextView chooseHint, backHint;
    private Boolean shownRight; // the mapping the hints last showed

    /** What the choosing button does on this screen, for its hint; null for nothing. */
    abstract String chooseLabel();

    /** Where a pad lands when nothing has focus yet. */
    abstract View firstFocus();

    /** Whether the pad's back button leaves this screen. */
    boolean padGoesBack() {
        return true;
    }

    /** Whether the right button launches on this screen. */
    boolean launchRight() {
        return Settings.launchRight(this);
    }

    /** Back from settings with the buttons swapped: the hints flash to say so. */
    @Override
    protected void onResume() {
        super.onResume();
        boolean changed = shownRight != null && shownRight != launchRight();
        showHints();
        if (changed) flashHints();
    }

    /**
     * The face buttons act here and are always consumed. Handing them on as
     * other keys did not work: after a touch nothing has focus, the stand-in key
     * went unhandled, and Android then ran the button's own fallback (B is
     * Back), whatever the setting said.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            // adb logcat -s wasmcart: which key each button really sends
            Log.i("wasmcart", getClass().getSimpleName() + " key " + KeyEvent.keyCodeToString(code)
                    + " from " + (e.getDevice() != null ? e.getDevice().getName() : "?")
                    + ", launch right " + launchRight());
        }
        if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (Settings.bottomCode(this) == 0) {
                askBottomButton(null);
                return true;
            }
            boolean bottom = code == Settings.bottomCode(this);
            boolean choose = bottom != launchRight();
            if (!choose) {
                if (padGoesBack()) onBackPressed();
            } else if (chooseLabel() != null) {
                choose();
            }
        }
        return true;
    }

    /**
     * Asks for the bottom face button and remembers the key it sends, so every
     * menu and game knows where each button really is.
     */
    void askBottomButton(Runnable done) {
        TextView prompt = new TextView(this);
        prompt.setText("Press the bottom face button on your controller.");
        prompt.setTextSize(18);
        prompt.setGravity(Gravity.CENTER_VERTICAL);
        prompt.setCompoundDrawablePadding(dp(14));
        prompt.setCompoundDrawables(icon(ButtonIcon.BOTTOM, Color.WHITE), null, null, null);
        prompt.setPadding(dp(24), dp(24), dp(24), dp(24));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(prompt).create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BUTTON_A && keyCode != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Settings.setBottomCode(this, keyCode);
                Log.i("wasmcart", "bottom face button sends " + KeyEvent.keyCodeToString(keyCode));
                d.dismiss();
                flashHints();
                if (done != null) done.run();
            }
            return true;
        });
        dialog.show();
    }

    /** Activates what has focus. With nothing focused, the first press only shows where the pad is. */
    void choose() {
        View focused = getCurrentFocus();
        if (focused == null || focused.isInTouchMode()) {
            View first = firstFocus();
            if (first != null) {
                first.requestFocusFromTouch(); // leaves touch mode, so the highlight shows
                if (first instanceof ListView && ((ListView) first).getSelectedItemPosition() == AdapterView.INVALID_POSITION)
                    ((ListView) first).setSelection(0);
            }
            return;
        }
        if (focused instanceof ListView) {
            ListView list = (ListView) focused;
            int at = list.getSelectedItemPosition();
            if (at == AdapterView.INVALID_POSITION) {
                list.setSelection(0);
                return;
            }
            list.performItemClick(list.getSelectedView(), at, list.getItemIdAtPosition(at));
            return;
        }
        focused.performClick();
    }

    /** The hints for the bottom of the screen. */
    LinearLayout hints() {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(6), 0, dp(6));
        chooseHint = hint(chooseLabel() != null ? chooseLabel() : "");
        backHint = hint("Back");
        if (chooseLabel() != null) footer.addView(chooseHint);
        if (padGoesBack()) footer.addView(backHint);
        showHints();
        return footer;
    }

    static final int HINT_COLOR = Color.rgb(200, 205, 215);
    static final int FLASH_COLOR = Color.rgb(235, 64, 52);
    static final long FLASH_MS = 700;
    private int hintColor = HINT_COLOR;

    void showHints() {
        if (chooseHint == null) return;
        boolean right = launchRight();
        shownRight = right;
        showButtons(chooseHint, backHint, right, hintColor);
    }

    /** Puts the launching and back buttons' icons on two labels. */
    void showButtons(TextView launch, TextView back, boolean right, int color) {
        launch.setCompoundDrawables(icon(right ? ButtonIcon.RIGHT : ButtonIcon.BOTTOM, color), null, null, null);
        back.setCompoundDrawables(icon(right ? ButtonIcon.BOTTOM : ButtonIcon.RIGHT, color), null, null, null);
        launch.setTextColor(color);
        back.setTextColor(color);
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

    ButtonIcon icon(int filled, int color) {
        ButtonIcon icon = new ButtonIcon(filled, color);
        icon.setBounds(0, 0, dp(28), dp(28));
        return icon;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setCompoundDrawablePadding(dp(6));
        t.setPadding(0, 0, dp(20), 0);
        return t;
    }
}
