package dev.wasmcart.androidplayer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A face button, shown as the four-dot diamond with its position filled. Face
 * buttons are never named: pads disagree on which letter sits where, but every
 * pad has a bottom and a right button.
 */
final class ButtonIcon extends Drawable {
    static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;

    private final int filled;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ButtonIcon(int filled, int color) {
        this.filled = filled;
        paint.setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) * 0.5f;
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = s * 0.24f, stroke = r * 0.36f;
        float[][] at = { { 0, -s + r }, { s - r, 0 }, { 0, s - r }, { -s + r, 0 } };
        for (int i = 0; i < 4; i++) {
            if (i == filled) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx + at[i][0], cy + at[i][1], r, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                canvas.drawCircle(cx + at[i][0], cy + at[i][1], r - stroke * 0.5f, paint);
            }
        }
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
