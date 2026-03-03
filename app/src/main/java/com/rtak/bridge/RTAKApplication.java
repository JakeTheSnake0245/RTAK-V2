package com.rtak.bridge;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/**
 * RTAK Bridge Application — initialises the Chaquopy Python runtime and
 * notification channels required by the foreground service.
 */
public class RTAKApplication extends Application {

    private static final String TAG = "RTAKApplication";

    public static final String CHANNEL_ID = "rtak_bridge_service";
    public static final String CHANNEL_NAME = "RTAK Bridge Service";

    @Override
    public void onCreate() {
        super.onCreate();

        // ── Start Chaquopy Python runtime ──────────────────────────────
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
            Log.i(TAG, "Python runtime started via Chaquopy");
        }

        // ── Create notification channel for foreground service ─────────
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the RTAK Bridge TAK server and Reticulum running");
            channel.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
