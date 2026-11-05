package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.View;
import android.widget.TextView;

import java.util.List;

/**
 * Home's Now Playing card above the dock (media_controls in config.xml): an equaliser, the song and artist (OK opens
 * the app playing it), previous, play/pause and next, shown only while an app has music playing or paused. Listens only while Home is resumed.
 */
final class MediaBar implements MediaSessionManager.OnActiveSessionsChangedListener {
    private final Activity activity;
    private final TextView song;
    private final View card;
    private final TextView play;
    private final View prev;
    private final View next;
    private final EqualizerView eq;
    private final SeekView seek;
    private String iconFor;
    /** A paused song leaves Home after this long; playing again brings the card back. */
    private static final long PAUSED_SHOWN_MS = 10 * 60_000;
    /** The session closed with ✕: hidden until it plays again. */
    private android.media.session.MediaSession.Token dismissed;
    private final Runnable recheck = this::show;
    private final ComponentName access;
    private MediaController controller;
    private final MediaController.Callback changes = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { show(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { show(); }
        @Override public void onSessionDestroyed() { use(null); }
    };

    MediaBar(Activity activity) {
        this.activity = activity;
        card = activity.findViewById(R.id.mediaCard);
        song = activity.findViewById(R.id.btnMediaSong);
        play = activity.findViewById(R.id.btnMediaPlay);
        prev = activity.findViewById(R.id.btnMediaPrev);
        next = activity.findViewById(R.id.btnMediaNext);
        eq = activity.findViewById(R.id.mediaEq);
        seek = activity.findViewById(R.id.mediaSeek);
        access = new ComponentName(activity, MediaAccess.class);
        song.setOnClickListener(v -> openApp());
        play.setOnClickListener(v -> {
            if (controller == null) return;
            if (playing()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        activity.findViewById(R.id.btnMediaClose).setOnClickListener(v -> {
            if (controller == null) return;
            controller.getTransportControls().pause();
            dismissed = controller.getSessionToken();
            show();
        });
        prev.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToPrevious(); });
        next.setOnClickListener(v -> { if (controller != null) controller.getTransportControls().skipToNext(); });
    }

    void start() {
        MediaSessionManager sessions = activity.getSystemService(MediaSessionManager.class);
        try {
            sessions.addOnActiveSessionsChangedListener(this, access);
            onActiveSessionsChanged(sessions.getActiveSessions(access));
        } catch (SecurityException noAccess) {
            use(null);   // notification access not granted: no controls
        }
    }

    void stop() {
        try { activity.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this); }
        catch (RuntimeException ignored) { }
        use(null);
    }

    /** The app playing now, else the first one paused (music you can resume). */
    @Override public void onActiveSessionsChanged(List<MediaController> list) {
        MediaController best = null;
        if (list != null) for (MediaController c : list) {
            PlaybackState state = c.getPlaybackState();
            if (state == null) continue;
            if (state.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (best == null && state.getState() == PlaybackState.STATE_PAUSED) best = c;
        }
        use(best);
    }

    private void use(MediaController next) {
        boolean same = controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken());
        if (controller != null && !same) controller.unregisterCallback(changes);
        if (next != null && !same) next.registerCallback(changes);
        if (!same) controller = next;
        show();
    }

    private boolean playing() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void show() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        int code = state == null ? PlaybackState.STATE_NONE : state.getState();
        boolean active = code == PlaybackState.STATE_PLAYING || code == PlaybackState.STATE_BUFFERING;
        if (active) dismissed = null;
        song.removeCallbacks(recheck);
        long pausedFor = code == PlaybackState.STATE_PAUSED
                ? android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime() : Long.MAX_VALUE;
        boolean recentPause = pausedFor < PAUSED_SHOWN_MS && !controller.getSessionToken().equals(dismissed);
        if (recentPause) song.postDelayed(recheck, PAUSED_SHOWN_MS - pausedFor);
        boolean visible = active || recentPause;
        boolean hadFocus = card.hasFocus();
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            if (hadFocus) activity.findViewById(R.id.grid).requestFocus();
            return;
        }
        MediaMetadata data = controller.getMetadata();
        CharSequence title = data == null ? null : data.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = data == null ? null : data.getText(MediaMetadata.METADATA_KEY_ARTIST);
        song.setText(title == null ? controller.getPackageName() : artist == null ? title : title + " · " + artist);
        boolean on = playing();
        eq.setPlaying(on);
        seek.setController(controller);
        boolean hasLength = seek.duration() > 0;
        if (!hasLength && seek.hasFocus()) song.requestFocus();
        seek.setVisibility(hasLength ? View.VISIBLE : View.GONE);
        showAppIcon(controller.getPackageName());
        play.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.ic_pause : R.drawable.ic_play, 0, 0, 0);
        play.setContentDescription(activity.getString(on ? R.string.media_pause : R.string.media_play));
    }

    /** The playing app's icon on the song pill, so Meld, SmartTube or YouTube reads at a glance. */
    private void showAppIcon(String pkg) {
        if (pkg.equals(iconFor)) return;
        iconFor = pkg;
        android.graphics.drawable.Drawable icon;
        try { icon = activity.getPackageManager().getApplicationIcon(pkg); }
        catch (android.content.pm.PackageManager.NameNotFoundException gone) { icon = null; }
        if (icon != null) {
            int size = Math.round(22 * activity.getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
        }
        song.setCompoundDrawableTintList(null);   // the pill tints its icons white; keep the app's colours
        song.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private void openApp() {
        if (controller == null) return;
        // The app's own now-playing screen (the player, where Meld keeps the lyrics), else the app itself.
        android.app.PendingIntent player = controller.getSessionActivity();
        if (player != null) {
            try { player.send(); return; } catch (android.app.PendingIntent.CanceledException gone) { /* open the app */ }
        }
        String pkg = controller.getPackageName();
        Intent tv = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        Intent intent = tv != null ? tv : activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) activity.startActivity(intent);
    }
}
