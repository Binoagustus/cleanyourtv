package io.github.sumyabazar1.weather;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: Home's wallpaper with the live weather animated over it, a large clock and date, this month's calendar
 * with today marked, the weather now and the next three days. Open-Meteo is read only while it is showing.
 */
public final class WeatherDream extends DreamService {
    /** The launcher's wallpaper; readable with WRITE_SECURE_SETTINGS, granted once over ADB. */
    private static final Uri WALLPAPER = Uri.parse("content://com.gathin.tvhome.theme/wallpaper.jpg");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    private FrameLayout root;
    private SkyView sky;
    private LinearLayout weather, calendar;
    private LocalDate calendarDay;
    /** Now Playing: shown instead of the weather while music plays (Meld, Bluetooth Stereo, a video's audio). */
    private FrameLayout nowPlaying;
    private ImageView art, backdrop;
    private EqualizerView eq;
    private Bitmap artFor;
    private TextView song, artist, source;
    private SeekView progress;
    private MediaController current;
    private final Runnable listen = this::checkPlaying;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);   // the remote controls the music on Now Playing; any other key leaves
        setFullscreen(true);
        root = new FrameLayout(this);
        root.setBackground(Forecast.backdrop(3, true));
        ImageView wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, -1, -1);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x40000000, 0xB3000000}));
        root.addView(shade, -1, -1);
        sky = new SkyView(this);
        root.addView(sky, -1, -1);

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setTextSize(110);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setTextColor(0xFFFFFFFF);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setIncludeFontPadding(false);
        time.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(26);
        date.setTextColor(0xE6FFFFFF);
        time.addView(date);
        root.addView(time, place(Gravity.TOP | Gravity.START));

        calendar = new LinearLayout(this);
        calendar.setOrientation(LinearLayout.VERTICAL);
        calendar.setPadding(dp(20), dp(16), dp(20), dp(16));
        calendar.setBackground(glass());
        // Top centre, between the clock and the right third, where a wallpaper's subject (the tree) usually sits.
        root.addView(calendar, place(Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        buildCalendar();

        weather = new LinearLayout(this);
        weather.setGravity(Gravity.BOTTOM);
        root.addView(weather, place(Gravity.BOTTOM | Gravity.START));
        FrameLayout stage = new FrameLayout(this);
        stage.addView(root, -1, -1);
        stage.addView(buildNowPlaying(), -1, -1);
        setContentView(stage);

        io.execute(() -> {
            Bitmap bitmap = null;
            try (InputStream in = getContentResolver().openInputStream(WALLPAPER)) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                bitmap = BitmapFactory.decodeStream(in, null, options);
            } catch (Exception | OutOfMemoryError ignored) {
                // No launcher, or no grant: the weather gradient stays behind the scene.
            }
            Bitmap shown = bitmap;
            main.post(() -> { if (shown != null && root != null) wallpaper.setImageBitmap(shown); });
        });
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        checkPlaying();
        refresh();
    }

    @Override public void onDreamingStopped() {
        main.removeCallbacks(tick);
        main.removeCallbacks(listen);
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        root = null;
        super.onDetachedFromWindow();
    }

    /** Low power: a dark, still screen with the album art and the song; no sky animation, a small clock. */
    private View buildNowPlaying() {
        nowPlaying = new FrameLayout(this);
        nowPlaying.setBackgroundColor(0xFF05070A);
        nowPlaying.setVisibility(View.GONE);
        // The cover, blurred across the screen and darkened: made once per song from a tiny copy, so it costs nothing
        // to keep on screen.
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowPlaying.addView(backdrop, -1, -1);
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x99000000, 0xCC000000, 0xE6000000}));
        nowPlaying.addView(scrim, -1, -1);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        GradientDrawable corners = new GradientDrawable();
        corners.setCornerRadius(dp(18));
        corners.setColor(0xFF1A1E26);
        art.setBackground(corners);
        art.setElevation(dp(24));
        row.addView(art, new LinearLayout.LayoutParams(dp(340), dp(340)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(48), 0, 0, 0);
        LinearLayout label = new LinearLayout(this);
        label.setGravity(Gravity.CENTER_VERTICAL);
        eq = new EqualizerView(this, null);
        label.addView(eq, new LinearLayout.LayoutParams(dp(18), dp(16)));
        source = text("", 15, 0xB3FFFFFF);
        source.setLetterSpacing(0.12f);
        source.setPadding(dp(12), 0, 0, 0);
        label.addView(source);
        song = text("", 44, 0xF2FFFFFF);
        song.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        song.setMaxLines(2);
        song.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist = text("", 24, 0xA6FFFFFF);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(label);
        words.addView(song);
        words.addView(artist);
        progress = new SeekView(this, null);
        progress.setFocusable(false);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(30));
        bar.topMargin = dp(18);
        words.addView(progress, bar);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout.LayoutParams centre = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        centre.setMargins(dp(96), 0, dp(96), 0);
        nowPlaying.addView(row, centre);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(28);
        clock.setTextColor(0x8CFFFFFF);
        nowPlaying.addView(clock, place(Gravity.TOP | Gravity.END));
        TextView hint = text(getString(R.string.now_playing_keys), 14, 0x66FFFFFF);
        nowPlaying.addView(hint, place(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        return nowPlaying;
    }

    /** Every few seconds while dreaming: music playing shows Now Playing, otherwise the weather. */
    private void checkPlaying() {
        main.removeCallbacks(listen);
        if (root == null) return;
        MediaController playing = null;
        boolean audio = false;
        try {
            MediaSessionManager sessions = getSystemService(MediaSessionManager.class);
            MediaController paused = null;
            for (MediaController c : sessions.getActiveSessions(new ComponentName(this, NowPlayingListener.class))) {
                PlaybackState state = c.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) { playing = c; break; }
                if (state != null && state.getState() == PlaybackState.STATE_PAUSED && paused == null) paused = c;
            }
            // Paused from the remote: Now Playing stays, so OK can start it again.
            if (playing == null && nowPlaying.getVisibility() == View.VISIBLE) playing = paused;
        } catch (SecurityException noAccess) {
            // No notification access: still tell music from silence, without the song's details.
            audio = getSystemService(AudioManager.class).isMusicActive();
        }
        current = playing;
        boolean music = playing != null || audio;
        nowPlaying.setVisibility(music ? View.VISIBLE : View.GONE);
        sky.setVisibility(music ? View.GONE : View.VISIBLE);   // the animation stops while it is covered
        if (music) show(playing);
        main.postDelayed(listen, 3000);
    }

    private void show(MediaController playing) {
        MediaMetadata data = playing == null ? null : playing.getMetadata();
        PlaybackState state = playing == null ? null : playing.getPlaybackState();
        String label = getString(state != null && state.getState() == PlaybackState.STATE_PAUSED ? R.string.paused : R.string.now_playing);
        if (playing != null) {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                label += " · " + pm.getApplicationLabel(pm.getApplicationInfo(playing.getPackageName(), 0));
            } catch (Exception ignored) { }
        }
        source.setText(label.toUpperCase(Locale.getDefault()));
        String title = data == null ? null : first(data, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        song.setText(title != null ? title : getString(R.string.music_playing));
        String by = data == null ? null : first(data, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        artist.setText(by != null ? by : "");
        Bitmap picture = data == null ? null : data.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (picture == null && data != null) picture = data.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (picture == null && data != null) picture = data.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        progress.setController(playing);
        progress.setVisibility(progress.duration() > 0 ? View.VISIBLE : View.GONE);
        eq.setPlaying(state != null && state.getState() == PlaybackState.STATE_PLAYING);
        if (picture != artFor) {
            artFor = picture;
            art.setImageBitmap(picture);
            backdrop.setImageBitmap(picture == null ? null : Bitmap.createScaledBitmap(picture, 12, 12, true));
        }
        art.setVisibility(picture != null ? View.VISIBLE : View.GONE);
    }

    /** On Now Playing: OK plays or pauses, Right next, Left previous. Any other key, or any key on the weather, wakes. */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        boolean media = current != null && nowPlaying.getVisibility() == View.VISIBLE
                && (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER
                || key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
        if (!media) {
            finish();
            return true;
        }
        if (event.getAction() != android.view.KeyEvent.ACTION_UP) return true;
        MediaController.TransportControls controls = current.getTransportControls();
        if (key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) controls.skipToNext();
        else if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) controls.skipToPrevious();
        else if (current.getPlaybackState() != null && current.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) controls.pause();
        else controls.play();
        main.removeCallbacks(listen);
        main.postDelayed(this::checkPlaying, 600);
        return true;
    }

    private static String first(MediaMetadata data, String... keys) {
        for (String key : keys) {
            CharSequence value = data.getText(key);
            if (value != null && value.length() > 0) return value.toString();
        }
        return null;
    }

    private void refresh() {
        main.removeCallbacks(tick);
        if (!LocalDate.now().equals(calendarDay)) buildCalendar();
        SharedPreferences place = WeatherActivity.place(this);
        if (!place.contains("name")) return;
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        String name = place.getString("name", "");
        io.execute(() -> {
            Forecast.Report report;
            try { report = Forecast.fetch(lat, lon, Forecast.MIN_DAYS); }
            catch (Exception e) { report = null; }
            Forecast.Report result = report;
            main.post(() -> {
                if (root == null) return;
                if (result != null) show(result, name);
                main.postDelayed(tick, result != null ? Forecast.REFRESH_MS : Forecast.RETRY_MS);
            });
        });
    }

    private void show(Forecast.Report r, String name) {
        sky.setWeather(r.code, r.day);
        weather.removeAllViews();
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        now.addView(text(name.toUpperCase(Locale.getDefault()), 15, 0xB3FFFFFF));
        TextView temp = text(Forecast.degrees(r.tempC, false) + "°", 84, 0xFFFFFFFF);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temp.setIncludeFontPadding(false);
        now.addView(temp);
        now.addView(text(getString(Forecast.sky(r.code, r.day)) + " · "
                + getString(R.string.feels_like, Forecast.degrees(r.feelsC, false)), 20, 0xE6FFFFFF));
        weather.addView(now);
        for (Forecast.Day d : r.days) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(12), dp(18), dp(12));
            card.setBackground(glass());
            card.addView(text(d.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()), 16, 0xFFFFFFFF));
            card.addView(text(getString(Forecast.sky(d.code, true)), 14, 0xB3FFFFFF));
            card.addView(text(getString(R.string.high_low, Forecast.degrees(d.maxC, false), Forecast.degrees(d.minC, false)), 18, 0xFFFFFFFF));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(130), -2);
            lp.setMarginStart(dp(16));
            weather.addView(card, lp);
        }
    }

    /** This month as a grid, weeks from Monday, today in a white circle. */
    private void buildCalendar() {
        calendarDay = LocalDate.now();
        calendar.removeAllViews();
        TextView month = text(calendarDay.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                + " " + calendarDay.getYear(), 18, 0xFFFFFFFF);
        month.setPadding(dp(6), 0, 0, dp(8));
        calendar.addView(month);
        LinearLayout header = new LinearLayout(this);
        for (int i = 0; i < 7; i++) header.addView(cell(DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), 0x99FFFFFF, false));
        calendar.addView(header);
        LocalDate first = calendarDay.withDayOfMonth(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int days = calendarDay.lengthOfMonth();
        for (int week = 0; week * 7 < offset + days; week++) {
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < 7; i++) {
                int day = week * 7 + i - offset + 1;
                boolean weekend = i >= 5;
                row.addView(cell(day >= 1 && day <= days ? String.valueOf(day) : "",
                        weekend ? 0xFFFFB4A8 : 0xE6FFFFFF, day == calendarDay.getDayOfMonth()));
            }
            calendar.addView(row);
        }
    }

    private TextView cell(String value, int colour, boolean today) {
        TextView view = text(value, 15, today ? 0xFF10141C : colour);
        view.setGravity(Gravity.CENTER);
        if (today) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xFFFFFFFF);
            view.setBackground(circle);
        }
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        return view;
    }

    private GradientDrawable glass() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x33FFFFFF);
        shape.setStroke(1, 0x40FFFFFF);
        shape.setCornerRadius(dp(16));
        return shape;
    }

    private FrameLayout.LayoutParams place(int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        lp.setMargins(dp(64), dp(48), dp(64), dp(48));
        return lp;
    }

    private TextView text(String value, float size, int colour) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(colour);
        view.setShadowLayer(8, 0, 2, 0x80000000);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
