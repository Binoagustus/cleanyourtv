package io.github.sumyabazar1.weather;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * "Set as Home wallpaper" for a picture opened or shared from another app (Sofa's downloads, File Manager). The
 * picture is scaled to the screen and staged with the launcher's theme provider, which checks it and swaps it in
 * whole, so a broken file never leaves Home without a wallpaper. Nothing else on Home changes.
 */
public final class SetWallpaperActivity extends Activity {
    private static final String THEME = "content://com.gathin.tvhome.theme/";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        Uri picture = Intent.ACTION_SEND.equals(intent.getAction())
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
        if (picture == null) { done(false); return; }
        new Thread(() -> {
            boolean ok;
            try { ok = apply(picture); }
            catch (Exception | OutOfMemoryError e) { ok = false; }
            boolean result = ok;
            runOnUiThread(() -> done(result));
        }, "Set wallpaper").start();
    }

    private boolean apply(Uri picture) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(picture)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return false;
        // Big downloads are sampled down while decoding, then fitted to cover the screen: the launcher decodes one
        // screen-sized picture on a TV with 1 GB of RAM.
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= screenW && bounds.outHeight / (options.inSampleSize * 2) >= screenH) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(picture)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
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

    private void done(boolean ok) {
        Toast.makeText(this, ok ? R.string.wallpaper_set : R.string.wallpaper_failed, Toast.LENGTH_LONG).show();
        finish();
    }
}
