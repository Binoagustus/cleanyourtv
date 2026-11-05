package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

/**
 * The song's progress on Home's Now Playing card: elapsed, a track and the length. Focused, Left/Right drag a preview
 * knob that glides to its target and speeds up while held; the song jumps there 0.7 s after the last press. Redraws
 * twice a second, or at 30 fps while focused or dragging.
 */
public final class SeekView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private MediaController controller;
    private long target = -1;        // where a drag is heading, ms; -1 when not dragging
    private float shown = -1;        // the knob's drawn position, gliding toward target
    private long sentAt;             // when the seek went to the app; 0 while still dragging
    private final Runnable commit = () -> {
        if (controller == null || target < 0) return;
        controller.getTransportControls().seekTo(target);
        sentAt = SystemClock.elapsedRealtime();
        invalidate();
    };

    public SeekView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        track.setColor(0x40FFFFFF);
        fill.setColor(0xE6FFFFFF);
        text.setColor(0xB3FFFFFF);
        text.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        text.setFontFeatureSettings("tnum");
        setFocusable(true);
    }

    void setController(MediaController next) {
        if (next != controller) release();
        controller = next;
        invalidate();
    }

    /** The song's length in ms, or 0 for a stream that has none (the bar then hides). */
    long duration() {
        MediaMetadata data = controller == null ? null : controller.getMetadata();
        return data == null ? 0 : Math.max(0, data.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    private long position() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        if (state == null) return 0;
        long pos = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        return Math.max(0, Math.min(pos, duration()));
    }

    private void release() {
        removeCallbacks(commit);
        target = -1;
        shown = -1;
        sentAt = 0;
        invalidate();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        long length = duration();
        if ((!right && !left) || length <= 0) return super.onKeyDown(keyCode, event);
        // 5 s a press, growing while held to 30 s a step: a 4-minute song crosses in about two seconds.
        long step = Math.min(30_000, 5_000 + event.getRepeatCount() * 1_500L);
        if (target < 0) { target = position(); shown = target; }
        sentAt = 0;
        target = Math.max(0, Math.min(length, target + (right ? step : -step)));
        removeCallbacks(commit);
        postDelayed(commit, 700);
        invalidate();
        return true;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(commit);
        super.onDetachedFromWindow();
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override protected void onDraw(Canvas canvas) {
        long length = duration();
        if (length <= 0) return;
        // The knob stays on the seek until the app reports a position near it (or 4 s pass), so it never flicks back
        // to the old time while the app is still buffering the jump. While it waits, it moves on with the clock.
        if (sentAt > 0) {
            long waited = SystemClock.elapsedRealtime() - sentAt;
            boolean playing = controller != null && controller.getPlaybackState() != null
                    && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
            long expected = target + (playing ? waited : 0);
            if (Math.abs(position() - expected) < 1500 || waited > 4000) release();
            else shown = expected;
        }
        if (target >= 0 && sentAt == 0) shown += (target - shown) * 0.3f;   // glide, not jump
        float pos = target >= 0 ? shown : position();
        boolean focused = isFocused();
        String end = time(length), now = time((long) pos);
        float pad = text.measureText(end) + 12 * dp;
        float cy = getHeight() / 2f, base = cy - (text.descent() + text.ascent()) / 2;
        text.setAlpha(focused ? 255 : 180);
        canvas.drawText(now, 0, base, text);
        canvas.drawText(end, getWidth() - text.measureText(end), base, text);
        float x0 = pad, x1 = getWidth() - pad, h = (focused ? 5 : 3) * dp;
        float x = x0 + (x1 - x0) * Math.min(1f, pos / length);
        canvas.drawRoundRect(x0, cy - h / 2, x1, cy + h / 2, h, h, track);
        canvas.drawRoundRect(x0, cy - h / 2, Math.max(x0 + h, x), cy + h / 2, h, h, fill);
        if (focused) canvas.drawCircle(x, cy, 8 * dp, fill);
        if (isShown()) postInvalidateDelayed(focused || target >= 0 ? 33 : 500);
    }
}
