package io.github.sumyabazar1.weather;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The live weather, animated like a window onto it: rain with fading tails in three depths, swaying with gusts,
 * soft drops close to the eye and splashes on the ground; layered clouds with darker storm undersides that the
 * lightning lights from inside; snow with depth of field; the sun's glow with lens glints, or twinkling stars.
 * Follows the WMO code Open-Meteo reports, draws at most 30 frames a second, and stops while it is off screen.
 */
final class SkyView extends View {
    private static final int SUN = 0, STARS = 1, CLOUDS = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;
    private static final int MAX = 600, LAYER = MAX / 3, SPLASHES = 48;

    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG), tail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] x = new float[MAX], y = new float[MAX], depth = new float[MAX], phase = new float[MAX];
    private final float[] heads = new float[MAX * 4], tails = new float[MAX * 4];
    private final float[] sx = new float[SPLASHES], sy = new float[SPLASHES], sAge = new float[SPLASHES];
    private final float[] bolt = new float[64];
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private Bitmap cloud, dot;
    private RadialGradient glow;
    private int kind = CLOUDS, count, boltPoints, nextSplash;
    private float clouds, baseWind, wind, flash, darkness;
    private long last, nextStrike;

    SkyView(Context context) {
        super(context);
        head.setStrokeCap(Paint.Cap.ROUND);
        tail.setStrokeCap(Paint.Cap.ROUND);
        glowLine.setStrokeCap(Paint.Cap.ROUND);
        glowLine.setStyle(Paint.Style.STROKE);
        glowLine.setMaskFilter(new BlurMaskFilter(14, BlurMaskFilter.Blur.NORMAL));
        for (int i = 0; i < SPLASHES; i++) sAge[i] = 1;
    }

    /** The weather to show: the WMO code and whether it is day where the place is. */
    void setWeather(int code, boolean day) {
        clouds = 0;
        darkness = 0;
        baseWind = 0.16f;
        if (code <= 1) { kind = day ? SUN : STARS; count = day ? 0 : 160; clouds = code == 1 ? 0.3f : 0; }
        else if (code == 2) { kind = day ? SUN : STARS; count = day ? 0 : 100; clouds = 0.6f; }
        else if (code == 3) { kind = CLOUDS; clouds = 1; darkness = 0.25f; }
        else if (code == 45 || code == 48) { kind = FOG; clouds = 0.7f; }
        else if (code >= 51 && code <= 57) { kind = RAIN; count = 180; clouds = 0.85f; darkness = 0.3f; baseWind = 0.06f; }
        else if (code == 61 || code == 66 || code == 80) { kind = RAIN; count = 280; clouds = 0.95f; darkness = 0.4f; }
        else if (code == 63 || code == 81) { kind = RAIN; count = 420; clouds = 1; darkness = 0.5f; baseWind = 0.2f; }
        else if (code == 65 || code == 67 || code == 82) { kind = RAIN; count = 580; clouds = 1; darkness = 0.6f; baseWind = 0.28f; }
        else if (code == 71 || code == 85 || code == 77) { kind = SNOW; count = 140; clouds = 0.8f; darkness = 0.1f; }
        else if (code == 73) { kind = SNOW; count = 240; clouds = 0.9f; darkness = 0.15f; }
        else if (code == 75 || code == 86) { kind = SNOW; count = 380; clouds = 1; darkness = 0.2f; baseWind = 0.3f; }
        else if (code >= 95) { kind = STORM; count = 520; clouds = 1; darkness = 0.7f; baseWind = 0.26f; }
        else { kind = CLOUDS; clouds = 1; darkness = 0.3f; }
        if (!day) darkness = Math.min(1, darkness + 0.3f);   // clouds at night are lit by nothing but the town
        wind = baseWind;
        if (getWidth() > 0) scatter();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (cloud == null) { cloud = softCloud(); dot = softDot(); }
        glow = new RadialGradient(w * 0.84f, h * 0.1f, h * 0.7f,
                new int[]{0x70FFEFC4, 0x26FFD38A, 0x00FFC870}, new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP);
        scatter();
    }

    private void scatter() {
        for (int i = 0; i < count; i++) respawn(i, true);
        nextStrike = SystemClock.uptimeMillis() + 2500 + random.nextInt(5000);
    }

    private void respawn(int i, boolean anywhere) {
        float w = getWidth(), h = getHeight();
        depth[i] = random.nextFloat();
        x[i] = random.nextFloat() * (w + h * 0.5f) - h * 0.4f;
        y[i] = anywhere ? random.nextFloat() * h : -random.nextFloat() * h * 0.3f;
        phase[i] = random.nextFloat() * 6.283f;
        if (kind == STARS) { y[i] = random.nextFloat() * h * 0.65f; x[i] = random.nextFloat() * w; }
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        float w = getWidth(), h = getHeight(), t = now / 1000f;
        // Gusts: the wind breathes, so rain and snow sway rather than fall at one fixed angle.
        wind = baseWind * (1 + 0.45f * (float) Math.sin(t * 0.31f) + 0.2f * (float) Math.sin(t * 1.07f + 1));
        if (kind == SUN) sun(canvas, w, h, t);
        if (kind == STARS) stars(canvas, t);
        if (darkness > 0) canvas.drawColor(Color.argb((int) (70 * darkness), 8, 12, 20));
        if (kind == STORM) strike(w, h, now);
        if (clouds > 0) cloudBanks(canvas, w, h, t);
        if (kind == RAIN || kind == STORM) { rain(canvas, w, h, dt); splashes(canvas, dt); }
        if (kind == SNOW) snow(canvas, w, h, dt, t);
        if (kind == STORM) lightning(canvas, dt);
        if (isShown()) postInvalidateDelayed(33);
    }

    private void sun(Canvas canvas, float w, float h, float t) {
        paint.setShader(glow);
        paint.setAlpha((int) (220 + 35 * Math.sin(t * 0.5f)));
        canvas.drawCircle(w * 0.84f, h * 0.1f, h * 0.7f, paint);
        paint.setShader(null);
        // Lens glints along the line from the sun through the centre, drifting a little as if the camera breathes.
        float drift = 0.02f * (float) Math.sin(t * 0.2f);
        float[][] ghosts = {{0.35f, 0.035f, 26}, {0.55f, 0.06f, 16}, {0.8f, 0.025f, 30}, {1.15f, 0.09f, 10}};
        src.set(0, 0, dot.getWidth(), dot.getHeight());
        for (float[] g : ghosts) {
            float k = g[0] + drift;
            float gx = w * 0.84f + (w * 0.5f - w * 0.84f) * 2 * k, gy = h * 0.1f + (h * 0.5f - h * 0.1f) * 2 * k;
            float r = h * g[1];
            dst.set(gx - r, gy - r, gx + r, gy + r);
            paint.setAlpha((int) g[2]);
            canvas.drawBitmap(dot, src, dst, paint);
        }
        paint.setAlpha(255);
    }

    private void stars(Canvas canvas, float t) {
        for (int i = 0; i < count; i++) {
            float twinkle = 0.55f + 0.45f * (float) Math.sin(t * (0.5f + depth[i] * 1.5f) + phase[i]);
            paint.setColor(Color.argb((int) (30 + 190 * twinkle * depth[i]), 255, 250, 240));
            canvas.drawCircle(x[i], y[i], 0.6f + 1.5f * depth[i] * depth[i], paint);
        }
    }

    /** Three parallax layers of cloud, far ones slow and pale, near ones faster and darker underneath in rain. */
    private void cloudBanks(Canvas canvas, float w, float h, float t) {
        src.set(0, 0, cloud.getWidth(), cloud.getHeight());
        boolean fog = kind == FOG;
        int lit = (int) (255 * Math.min(1, flash * 1.4f));
        for (int layer = 0; layer < 3; layer++) {
            int shade = (int) (255 - darkness * (165 + layer * 25));
            // Lightning lights the clouds from inside: white added on top of their shade.
            paint.setColorFilter(new LightingColorFilter(Color.rgb(shade, shade, Math.min(255, shade + 8)),
                    Color.rgb(lit * 3 / 4, lit * 3 / 4, lit)));
            for (int i = 0; i < 3; i++) {
                float speed = (fog ? 6 : 8) * (1 + layer * 0.8f);
                float cw = fog ? w * 1.4f : w * (0.55f + 0.15f * layer + 0.05f * i);
                float ch = fog ? h * 0.45f : cw * 0.45f;
                float cx = ((t * speed + i * (w + cw) / 3 + layer * w * 0.21f) % (w + cw)) - cw;
                float cy = fog ? h * (0.15f + layer * 0.25f + i * 0.04f) - ch / 2
                        : h * (-0.12f + 0.08f * layer + 0.05f * (i % 2)) - ch * 0.15f;
                float bob = (float) Math.sin(t * 0.13f + i + layer) * h * 0.008f;
                dst.set(cx, cy + bob, cx + cw, cy + ch + bob);
                paint.setAlpha((int) (255 * clouds * (fog ? 0.28f : 0.26f + 0.1f * layer)));
                canvas.drawBitmap(cloud, src, dst, paint);
            }
        }
        paint.setColorFilter(null);
        paint.setAlpha(255);
    }

    private void rain(Canvas canvas, float w, float h, float dt) {
        int[] used = new int[3];
        src.set(0, 0, dot.getWidth(), dot.getHeight());
        for (int i = 0; i < count; i++) {
            float d = depth[i];
            float speed = h * (1.0f + 1.6f * d);
            y[i] += speed * dt;
            x[i] += speed * wind * dt;
            float len = h * (0.02f + 0.07f * d);
            if (y[i] - len > h || x[i] > w + 40) {
                if (d > 0.6f) splash(x[i], h * (0.86f + 0.13f * random.nextFloat()));
                respawn(i, false);
                continue;
            }
            if (d > 0.97f) {
                // A drop right in front of the eye: big, soft and faint.
                dst.set(x[i] - 6, y[i] - len * 1.6f, x[i] + 6, y[i]);
                paint.setAlpha(26);
                canvas.drawBitmap(dot, src, dst, paint);
                continue;
            }
            int layer = d < 0.45f ? 0 : d < 0.8f ? 1 : 2;
            if (used[layer] >= LAYER) continue;
            int k = (layer * LAYER + used[layer]++) * 4;
            // A bright head and a longer, dimmer tail: a streak that fades upward, as a camera sees rain.
            float mx = x[i] - len * 0.35f * wind, my = y[i] - len * 0.35f;
            heads[k] = x[i]; heads[k + 1] = y[i]; heads[k + 2] = mx; heads[k + 3] = my;
            tails[k] = mx; tails[k + 1] = my; tails[k + 2] = x[i] - len * wind; tails[k + 3] = y[i] - len;
        }
        paint.setAlpha(255);
        for (int l = 0; l < 3; l++) {
            float width = 0.8f + l * 0.7f;
            head.setStrokeWidth(width);
            tail.setStrokeWidth(width * 0.8f);
            head.setColor(Color.argb(70 + l * 50, 214, 224, 240));
            tail.setColor(Color.argb(22 + l * 18, 200, 212, 232));
            canvas.drawLines(tails, l * LAYER * 4, used[l] * 4, tail);
            canvas.drawLines(heads, l * LAYER * 4, used[l] * 4, head);
        }
    }

    private void splash(float px, float py) {
        int i = nextSplash++ % SPLASHES;
        sx[i] = px;
        sy[i] = py;
        sAge[i] = 0;
    }

    /** Small rings where drops land, widening and fading in a quarter of a second. */
    private void splashes(Canvas canvas, float dt) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        for (int i = 0; i < SPLASHES; i++) {
            if (sAge[i] >= 1) continue;
            sAge[i] += dt * 4;
            float r = 2 + 14 * sAge[i];
            paint.setColor(Color.argb((int) (90 * Math.max(0, 1 - sAge[i])), 220, 228, 240));
            dst.set(sx[i] - r, sy[i] - r * 0.3f, sx[i] + r, sy[i] + r * 0.3f);
            canvas.drawOval(dst, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Snow with depth of field: far flakes small and sharp, near ones large and soft, each tumbling on its own. */
    private void snow(Canvas canvas, float w, float h, float dt, float t) {
        src.set(0, 0, dot.getWidth(), dot.getHeight());
        for (int i = 0; i < count; i++) {
            float d = depth[i];
            y[i] += h * (0.03f + 0.1f * d) * dt;
            x[i] += (h * wind * 0.1f * d + (float) Math.sin(t * (0.6f + d) + phase[i]) * 26 * d) * dt;
            if (y[i] > h + 20 || x[i] > w + 20) { respawn(i, false); continue; }
            if (d > 0.85f) {
                float r = 6 + 16 * (d - 0.85f) / 0.15f;
                dst.set(x[i] - r, y[i] - r, x[i] + r, y[i] + r);
                paint.setAlpha((int) (110 - 50 * (d - 0.85f) / 0.15f));
                canvas.drawBitmap(dot, src, dst, paint);
            } else {
                paint.setColor(Color.argb((int) (80 + 160 * d), 255, 255, 255));
                canvas.drawCircle(x[i], y[i], 1 + 3.2f * d * d, paint);
            }
        }
        paint.setAlpha(255);
    }

    /** Picks the moment and shape of the next strike: a jagged channel with a few short branches. */
    private void strike(float w, float h, long now) {
        if (now < nextStrike) return;
        flash = 1;
        nextStrike = now + 3500 + random.nextInt(9000);
        float bx = w * (0.2f + random.nextFloat() * 0.6f), by = h * 0.05f;
        boltPoints = 0;
        while (by < h * 0.7f && boltPoints <= bolt.length - 8) {
            float nx = bx + (random.nextFloat() - 0.5f) * w * 0.05f, ny = by + h * (0.03f + random.nextFloat() * 0.05f);
            bolt[boltPoints++] = bx; bolt[boltPoints++] = by; bolt[boltPoints++] = nx; bolt[boltPoints++] = ny;
            if (random.nextFloat() < 0.2f) {
                bolt[boltPoints++] = nx; bolt[boltPoints++] = ny;
                bolt[boltPoints++] = nx + (random.nextFloat() - 0.5f) * w * 0.12f;
                bolt[boltPoints++] = ny + h * (0.05f + random.nextFloat() * 0.06f);
            }
            bx = nx;
            by = ny;
        }
    }

    private void lightning(Canvas canvas, float dt) {
        if (flash <= 0) return;
        // A double flicker, fading in about half a second; the clouds were lit in cloudBanks.
        float flicker = flash > 0.75f || (flash > 0.35f && flash < 0.5f) ? flash : flash * 0.3f;
        canvas.drawColor(Color.argb((int) (45 * flicker), 200, 210, 255));
        if (flash > 0.3f) {
            glowLine.setColor(Color.argb((int) (150 * flicker), 170, 190, 255));
            glowLine.setStrokeWidth(10);
            canvas.drawLines(bolt, 0, boltPoints, glowLine);
            paint.setColor(Color.argb((int) (255 * flicker), 245, 247, 255));
            paint.setStrokeWidth(2.2f);
            canvas.drawLines(bolt, 0, boltPoints, paint);
        }
        flash -= dt * 1.8f;
    }

    /** One soft cloud, drawn once and reused for every bank; every puff fades out inside the bitmap, so no hard edges. */
    private static Bitmap softCloud() {
        int w = 512, h = 256;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint puff = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[][] puffs = {{0.28f, 0.6f, 0.2f}, {0.46f, 0.5f, 0.21f}, {0.66f, 0.56f, 0.2f}, {0.38f, 0.42f, 0.15f},
                {0.58f, 0.38f, 0.16f}, {0.78f, 0.64f, 0.14f}, {0.5f, 0.62f, 0.18f}};
        for (float[] p : puffs) {
            float r = p[2] * w;
            // Fades to nothing at its rim, so overlapping puffs never show an edge.
            puff.setShader(new RadialGradient(p[0] * w, p[1] * h, r, new int[]{0x99F2F4F8, 0x4DD0D6DF, 0x14C0C6D0, 0x00B8BEC8},
                    new float[]{0, 0.45f, 0.8f, 1}, Shader.TileMode.CLAMP));
            canvas.drawCircle(p[0] * w, p[1] * h, r, puff);
        }
        return bitmap;
    }

    /** A soft round light, for near drops, near flakes and lens glints. */
    private static Bitmap softDot() {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(32, 32, 32, new int[]{0xFFFFFFFF, 0x88FFFFFF, 0x00FFFFFF},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        new Canvas(bitmap).drawCircle(32, 32, 32, p);
        return bitmap;
    }
}
