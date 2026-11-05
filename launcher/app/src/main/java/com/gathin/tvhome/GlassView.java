package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix sweepAt = new android.graphics.Matrix();
    /** 0..1 while a light streak crosses the glass after it lands on a tile; -1 when still. */
    private float sweep = -1;
    private android.animation.ValueAnimator sweeper;
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0x000000;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0;
    /** glass_style rim or halo: no sheen and no fill, only the crisp rim (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;
    private boolean accented;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0x38FFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the slab as a texture. The spring only changes translation, so the GPU can
        // move it without replaying clipPath and the glow on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        accented = argb != 0;
        if (argb == 0) {
            // Plain glass: a soft dark shadow under it and only a faint edge, so the slab reads as glass sliding
            // between the icons, not a box drawn round one.
            glowRgb = 0x000000;
            glowAlpha = 0;   // the lifted tile's own shadow does this now (AppAdapter.FOCUS_LIFT_DP)
            rim.setColor(0x38FFFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rim.setColor(0xE6000000 | (r << 16) | (g << 8) | b);
        }
        invalidate();
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        flat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        rim.setStrokeWidth((flat ? 2f : 1.5f) * density);
        invalidate();
    }

    /**
     * One soft diagonal streak of light across the glass as it lands on a tile (Apple Card / glass-material specular),
     * then still: focus reads at a glance without a border. Ease-out, 700 ms; drawn with a shader on the slab's own
     * round rect, so no clip path (software on the MT5895).
     */
    void sweep() {
        if (sweeper != null) sweeper.cancel();
        sweeper = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(700);
        sweeper.setStartDelay(120);   // after the spring has mostly arrived
        sweeper.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        sweeper.addUpdateListener(a -> { sweep = (float) a.getAnimatedValue(); invalidate(); });
        sweeper.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { sweep = -1; invalidate(); }
        });
        sweeper.start();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // A narrow band, tilted like light off a card, faded at both edges.
        float band = Math.max(w, h) * 0.35f;
        sweepPaint.setShader(new LinearGradient(0, 0, band, band * 0.6f,
                new int[]{0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: thin rings across the inset, each a little fainter, so it fades out smoothly. Three wide
        // strokes left visible bands once the glow took an app's colour at a stronger alpha. The hardware layer
        // caches the result, so the rings cost one draw, not one per frame of the spring.
        if (!flat || halo) {
            int rings = 12;
            float step = inset / rings;
            int alpha = flat ? glowAlpha / 2 : glowAlpha;
            glow.setStrokeWidth(step + 0.5f);
            for (int i = 0; i < rings; i++) {
                float o = step * (i + 0.5f);
                float t = 1f - (float) i / rings;
                glow.setColor(Math.round(alpha * t) << 24 | glowRgb);
                canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
            }
        }
        if (flat) {
            canvas.drawRoundRect(rect, radius, radius, rim);
            return;
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        if (sweep >= 0) {
            float band = Math.max(getWidth(), getHeight()) * 0.35f;
            sweepAt.setTranslate(rect.left - band + (rect.width() + band) * sweep, rect.top);
            sweepPaint.getShader().setLocalMatrix(sweepAt);
            canvas.drawRoundRect(rect, radius, radius, sweepPaint);
        }

        // In an app's colour the glow alone marks the tile: no rim around it.
        if (!accented) canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
