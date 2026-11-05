package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Reads no notifications. Android lists what is playing (media sessions) only to an app with notification access,
 * named by a listener like this one: {@code cmd notification allow_listener com.gathin.tvhome/.MediaAccess}.
 * Used by MediaBar, Home's play/pause and next buttons.
 */
public class MediaAccess extends NotificationListenerService { }
