package io.github.sumyabazar1.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Copy of the launcher's EqualizerView, beside the app name on the screensaver's Now Playing; still and low while paused. ~20 fps, only while playing. */
public final class EqualizerView extends View {
    private static final int BARS = 4;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean playing;

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFFFFFFFF);
    }

    void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float gap = w / (BARS * 3f - 1), bar = gap * 2, r = bar / 2;
        double t = SystemClock.uptimeMillis() / 1000.0;
        paint.setAlpha(playing ? 230 : 140);
        for (int i = 0; i < BARS; i++) {
            // Two sines per bar at unrelated speeds read as music rather than a loop.
            double level = playing ? 0.55 + 0.25 * Math.sin(t * (5.1 + i * 1.7) + i * 2.3) + 0.2 * Math.sin(t * (8.3 - i) + i)
                    : 0.25;
            float barH = (float) Math.max(bar, h * Math.min(1, level));
            float x = i * (bar + gap);
            canvas.drawRoundRect(x, h - barH, x + bar, h, r, r, paint);
        }
        if (playing && isShown()) postInvalidateDelayed(50);
    }
}
