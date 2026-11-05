package com.gathin.christmas;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.job.JobScheduler;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home wallpaper: your ten newest pictures (Sofa downloads, File Manager, USB) as thumbnails; OK sets one as Home's
 * wallpaper. The picture is scaled to the screen and handed to the launcher's theme provider, which checks it and
 * swaps it in whole, so a broken file never leaves Home blank. Nothing else on Home changes. No network, no
 * background work: it only runs while it is on screen.
 */
public class WallpaperActivity extends Activity {
    private static final String THEME = "content://com.gathin.tvhome.theme/";
    private static final int COUNT = 10, COLUMNS = 5;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<View> cards = new ArrayList<>();
    private LinearLayout gallery;
    private TextView status, name, detail;
    private ImageView backdrop;
    private boolean busy;

    /** One picture: a download from the media store, or the Christmas picture that comes with the app (id -1). */
    private static final class Picture {
        final long id;
        final long added;
        Picture(long id, long added) { this.id = id; this.added = added; }
        boolean included() { return id < 0; }
        Uri uri() { return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id); }
    }

    private InputStream open(Picture picture) throws java.io.IOException {
        return picture.included() ? getAssets().open("christmas.jpg") : getContentResolver().openInputStream(picture.uri());
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The Christmas download jobs of the earlier version are gone; drop any left scheduled.
        getSystemService(JobScheduler.class).cancelAll();
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF090C14);
        // The picture you are on fills the screen behind the list, so you see it before choosing.
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, -1, -1);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9090C14, 0x8C090C14, 0xE6090C14}));
        root.addView(scrim, -1, -1);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(36), dp(48), dp(32));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        root.addView(page, -1, -1);
        page.addView(text("Gustus Wallpaper", 30, 0xFFF3F6FA));
        status = text("Choose a picture for Home. Pictures you download in Sofa appear here first.", 15, 0xD9E7EDF5);
        status.setPadding(0, dp(4), 0, dp(24));
        page.addView(status);
        gallery = new LinearLayout(this);
        gallery.setOrientation(LinearLayout.VERTICAL);
        gallery.setClipChildren(false);
        gallery.setClipToPadding(false);
        page.addView(gallery);
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.BOTTOM);
        name = text("", 22, 0xFFF3F6FA);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        detail = text("", 15, 0xD9E7EDF5);
        detail.setPadding(0, dp(4), 0, 0);
        footer.addView(name);
        footer.addView(detail);
        page.addView(footer, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        workers.execute(() -> {
            List<Picture> pictures = newest();
            runOnUiThread(() -> { if (!isDestroyed()) render(pictures); });
        });
    }

    private List<Picture> newest() {
        List<Picture> pictures = new ArrayList<>();
        String[] columns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED};
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && pictures.size() < COUNT - 1) {
                pictures.add(new Picture(c.getLong(0), c.getLong(1)));
            }
        } catch (RuntimeException noAccess) {
            // No storage grant: only the included picture shows.
        }
        pictures.add(new Picture(-1, 0));
        return pictures;
    }

    private void render(List<Picture> pictures) {
        long current = getSharedPreferences("home", MODE_PRIVATE).getLong("current", Long.MIN_VALUE);
        // Fixed-size cards: one picture or ten, each is a fifth of the row.
        int cardWidth = (getResources().getDisplayMetrics().widthPixels - dp(96) - dp(20) * (COLUMNS - 1)) / COLUMNS;
        int imageHeight = cardWidth * 9 / 16;
        LinearLayout line = null;
        for (int index = 0; index < pictures.size(); index++) {
            if (index % COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, imageHeight + dp(36));
                rowParams.bottomMargin = dp(20);
                gallery.addView(line, rowParams);
            }
            Picture picture = pictures.get(index);
            boolean isCurrent = picture.id == current;
            LinearLayout card = new LinearLayout(this);
            card.setId(View.generateViewId());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(3), dp(3), dp(3), dp(3));
            card.setBackground(frame(false));
            card.setFocusable(true);
            card.setClickable(true);
            String title = title(picture);
            card.setContentDescription(title + (isCurrent ? ", your wallpaper now" : ", press OK to use it"));
            card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName(Button.class.getName());
                }
            });
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setAlpha(0f);
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(thumbnail, new LinearLayout.LayoutParams(-1, 0, 1));
            String shortTitle = picture.included() ? "Christmas tree"
                    : DateUtils.getRelativeTimeSpanString(picture.added * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
            TextView label = text(isCurrent ? "✓ On Home now" : shortTitle, 13, 0xFFE7EDF5);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(6), 0, dp(6), 0);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(label, new LinearLayout.LayoutParams(-1, dp(30)));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(cardWidth, -1);
            if (index % COLUMNS < COLUMNS - 1) cell.rightMargin = dp(20);
            line.addView(card, cell);
            cards.add(card);
            card.setOnFocusChangeListener((view, focused) -> {
                card.setBackground(frame(focused));
                card.animate().cancel();
                boolean animate = ValueAnimator.areAnimatorsEnabled();
                float scale = focused && animate ? 1.06f : 1;
                card.animate().scaleX(scale).scaleY(scale).translationZ(focused ? dp(6) : 0)
                        .setDuration(animate ? 140 : 0).setInterpolator(new DecelerateInterpolator()).start();
                if (focused) {
                    name.setText(title);
                    detail.setText(isCurrent ? "This is your Home wallpaper now" : "Press OK to use this picture on Home");
                    Object preview = thumbnail.getTag();
                    if (preview instanceof Bitmap) showBackdrop((Bitmap) preview);
                }
            });
            card.setOnClickListener(view -> apply(picture));
            workers.execute(() -> {
                Bitmap bitmap = thumbnail(picture);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (bitmap == null) { label.setText("Preview unavailable"); return; }
                    thumbnail.setImageBitmap(bitmap);
                    thumbnail.setTag(bitmap);
                    if (card.hasFocus()) showBackdrop(bitmap);
                    thumbnail.animate().alpha(1).setDuration(ValueAnimator.areAnimatorsEnabled() ? 180 : 0).start();
                });
            });
        }
        int columns = Math.min(COLUMNS, cards.size());
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            card.setNextFocusLeftId(cards.get(i % COLUMNS == 0 ? i : i - 1).getId());
            card.setNextFocusRightId(cards.get(i % COLUMNS == columns - 1 || i + 1 == cards.size() ? i : i + 1).getId());
            card.setNextFocusUpId(cards.get(i < COLUMNS ? i : i - COLUMNS).getId());
            card.setNextFocusDownId(cards.get(i + COLUMNS < cards.size() ? i + COLUMNS : i).getId());
        }
        int start = 0;
        for (int i = 0; i < pictures.size(); i++) if (pictures.get(i).id == current) start = i;
        cards.get(start).requestFocus();
    }

    private String title(Picture picture) {
        if (picture.included()) return "Christmas tree (included)";
        return "Downloaded " + DateUtils.getRelativeTimeSpanString(picture.added * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    }

    /** The focused picture behind everything, fading in. */
    private void showBackdrop(Bitmap bitmap) {
        backdrop.setImageBitmap(bitmap);
        backdrop.setAlpha(0.4f);
        backdrop.animate().alpha(1).setDuration(ValueAnimator.areAnimatorsEnabled() ? 220 : 0).start();
    }

    /** The system's cached thumbnail where there is one (Android 10+), else a sampled decode. */
    private Bitmap thumbnail(Picture picture) {
        try {
            if (!picture.included() && Build.VERSION.SDK_INT >= 29) return getContentResolver().loadThumbnail(picture.uri(), new Size(640, 360), null);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = open(picture)) { return BitmapFactory.decodeStream(in, null, options); }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void apply(Picture picture) {
        if (busy) return;
        busy = true;
        detail.setText("Setting your wallpaper…");
        workers.execute(() -> {
            boolean ok;
            try { ok = stage(picture); }
            catch (Exception | OutOfMemoryError e) { ok = false; }
            boolean result = ok;
            if (ok) getSharedPreferences("home", MODE_PRIVATE).edit().putLong("current", picture.id).apply();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                if (result) {
                    Toast.makeText(this, "Home wallpaper set", Toast.LENGTH_SHORT).show();
                    finish();   // back to Home, which rebuilds with the new picture
                } else {
                    detail.setText("Couldn't use this picture. Your wallpaper is unchanged.");
                }
            });
        });
    }

    /** Scales the picture to cover the screen and lets the launcher swap it in. */
    private boolean stage(Picture picture) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(picture)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return false;
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= screenW && bounds.outHeight / (options.inSampleSize * 2) >= screenH) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream in = open(picture)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) return false;
        float scale = Math.max((float) screenW / bitmap.getWidth(), (float) screenH / bitmap.getHeight());
        if (scale < 1) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        try (OutputStream out = getContentResolver().openOutputStream(Uri.parse(THEME + "wallpaper_next.jpg"), "w")) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) return false;
        } finally {
            bitmap.recycle();
        }
        return getContentResolver().update(Uri.parse(THEME + "refresh"), new ContentValues(), null, null) == 1;
    }

    private GradientDrawable frame(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF141B26);
        shape.setCornerRadius(dp(8));
        shape.setStroke(dp(focused ? 2 : 1), focused ? 0xFFF3F6FA : 0xFF303B4C);
        return shape;
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        workers.shutdownNow();
        super.onDestroy();
    }
}
