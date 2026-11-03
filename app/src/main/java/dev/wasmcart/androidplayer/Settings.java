package dev.wasmcart.androidplayer;

import android.content.Context;
import android.content.SharedPreferences;

/** The player's settings, for every cart. SettingsActivity changes them. */
final class Settings {
    private static final String PREFS = "library";
    private static final String KEY_SWAP_FACE = "swap_face";
    private static final String KEY_MAX_HEIGHT = "max_height";
    private static final String KEY_SHOW_FPS = "show_fps";

    /** Resolution choices: the tallest picture offered to a cart, 0 for the screen's. */
    static final int[] MAX_HEIGHTS = { 0, 1080, 720, 540 };

    private Settings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The pad sends its bottom button as B and its left as Y, as Nintendo-style
     * pads (and some handhelds in that mode) do, where Android expects A at the
     * bottom. Swapping puts every face button back where it physically is, in
     * menus and in games.
     */
    static boolean swapFace(Context context) {
        return prefs(context).getBoolean(KEY_SWAP_FACE, false);
    }

    static void setSwapFace(Context context, boolean swap) {
        prefs(context).edit().putBoolean(KEY_SWAP_FACE, swap).apply();
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
