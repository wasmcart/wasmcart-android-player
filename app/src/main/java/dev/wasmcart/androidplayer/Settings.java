package dev.wasmcart.androidplayer;

import android.content.Context;
import android.content.SharedPreferences;

/** The player's settings, for every cart. SettingsActivity changes them. */
final class Settings {
    private static final String PREFS = "library";
    private static final String KEY_LAUNCH_RIGHT = "launch_right";
    private static final String KEY_BOTTOM_CODE = "bottom_code";
    private static final String KEY_MAX_HEIGHT = "max_height";
    private static final String KEY_SHOW_FPS = "show_fps";

    /** Resolution choices: the tallest picture offered to a cart, 0 for the screen's. */
    static final int[] MAX_HEIGHTS = { 0, 1080, 720, 540 };

    private Settings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * In menus, the right face button launches and the bottom one goes back, as
     * on Nintendo consoles, instead of the other way round. Games see every
     * button as it is.
     */
    static boolean launchRight(Context context) {
        return prefs(context).getBoolean(KEY_LAUNCH_RIGHT, false);
    }

    static void setLaunchRight(Context context, boolean right) {
        prefs(context).edit().putBoolean(KEY_LAUNCH_RIGHT, right).apply();
    }

    /**
     * The key code the pad's bottom face button sends, 0 until the player has
     * shown it. Android means A to be the bottom one, but devices disagree: a
     * Retroid Pocket 5 in its default mode sends B from there. Asked once.
     */
    static int bottomCode(Context context) {
        return prefs(context).getInt(KEY_BOTTOM_CODE, 0);
    }

    static void setBottomCode(Context context, int code) {
        prefs(context).edit().putInt(KEY_BOTTOM_CODE, code).apply();
    }

    /** The pad sends B from the bottom (and, with it, Y from the left). */
    static boolean bottomSendsB(Context context) {
        return bottomCode(context) == android.view.KeyEvent.KEYCODE_BUTTON_B;
    }

    /** A weaker GPU keeps up at a smaller picture, which fills the screen anyway. */
    static int maxHeight(Context context) {
        return prefs(context).getInt(KEY_MAX_HEIGHT, 0);
    }

    static void setMaxHeight(Context context, int height) {
        prefs(context).edit().putInt(KEY_MAX_HEIGHT, height).apply();
    }

    static String resolutionLabel(int height) {
        return height == 0 ? "Full screen" : height + "p";
    }

    static boolean showFps(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_FPS, false);
    }

    static void setShowFps(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW_FPS, show).apply();
    }
}
