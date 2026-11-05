package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets tools/theme.sh write the theme files into {@link Config#dir} on firmware where the adb shell is
 * locked out of Android/data (TCL G07, Android 11). Only the files {@link ThemeFiles} allows, and only for
 * callers holding WRITE_SECURE_SETTINGS, which the adb shell has and ordinary apps cannot get.
 *
 *   content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/config.json
 *   content write --uri content://com.gathin.tvhome.theme/wallpapers/dusk.jpg < /data/local/tmp/dusk.jpg
 *   content delete --uri content://com.gathin.tvhome.theme/config.json
 *   content delete --uri content://com.gathin.tvhome.theme/wallpapers      (every image in the collection)
 */
public class ThemeProvider extends ContentProvider {

    private File file(Uri uri) throws FileNotFoundException {
        String path = ThemeFiles.path(uri.getPathSegments());
        File dir = Config.dir(getContext());
        if (dir == null || path == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(dir, path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (mode.contains("w")) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File dir = Config.dir(getContext());
        if (dir != null && ThemeFiles.isCollection(uri.getPathSegments())) {
            File[] images = new File(dir, ThemeFiles.WALLPAPERS).listFiles((d, name) -> ThemeFiles.collectionImage(name));
            int n = 0;
            if (images != null) for (File f : images) if (f.delete()) n++;
            return n;
        }
        try {
            return file(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) {
        if (!"refresh".equals(uri.getLastPathSegment())) return 0;
        File dir = Config.dir(getContext());
        if (dir == null) return 0;
        File pending = new File(dir, "wallpaper_next.jpg");
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(pending.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1
                || !pending.renameTo(new File(dir, "wallpaper.jpg"))) return 0;
        android.content.SharedPreferences choices = getContext().getSharedPreferences(
                Config.SETTINGS_NAME, android.content.Context.MODE_PRIVATE);
        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                .putLong(Config.SETTINGS_REVISION_KEY,
                        choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
        return 1;
    }
}
