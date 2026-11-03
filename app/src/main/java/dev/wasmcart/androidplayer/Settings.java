package dev.wasmcart.androidplayer;

import android.content.Context;
import android.content.SharedPreferences;

/** The player's settings, for every cart. SettingsActivity changes them. */
final class Settings {
    private static final String PREFS = "library";
    private static final String KEY_CONFIRM_RIGHT = "confirm_right";
    private static final String KEY_MAX_HEIGHT = "max_height";
    private static final String KEY_SHOW_FPS = "show_fps";

    /** Resolution choices: the tallest picture offered to a cart, 0 for the screen's. */
    static final int[] MAX_HEIGHTS = { 0, 1080, 720, 540 };

    private Settings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The right face button selects and the bottom one goes back, instead of the
     * other way round. Android puts its A on the bottom, but some pads and
     * handhelds send it from the right, and players differ too.
     */
    static boolean confirmRight(Context context) {
        return prefs(context).getBoolean(KEY_CONFIRM_RIGHT, false);
    }

    static void setConfirmRight(Context context, boolean right) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_RIGHT, right).apply();
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
