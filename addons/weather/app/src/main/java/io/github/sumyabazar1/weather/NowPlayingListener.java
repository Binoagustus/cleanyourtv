package io.github.sumyabazar1.weather;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself: Android shows other apps' media sessions (what is playing) only to an app with notification
 * access, which names a listener like this one. Granted once over ADB; WeatherDream uses it for Now Playing.
 */
public final class NowPlayingListener extends NotificationListenerService { }
