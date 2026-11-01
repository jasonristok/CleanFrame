/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Keeps the AirPlay receiver reachable while other apps are in front, like the AirPlay setting
 * of a TV: a foreground service (Android requires its notification) that owns the server.
 */
package com.cleanframe.firetv.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.cleanframe.firetv.MainActivity;
import com.cleanframe.firetv.R;

public final class AirPlayService extends Service {
    private static final String CHANNEL = "airplay";
    private static final int NOTIFICATION = 7000;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION, notification());
        AirPlayHub.get(this).startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() needs a startForeground(); this also refreshes the name shown.
        startForeground(NOTIFICATION, notification());
        if (!AirPlayHub.get(this).enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AirPlayHub.get(this).stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.airplay_channel), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(channel);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        return b.setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle(getString(R.string.airplay_ready, AirPlayHub.get(this).name()))
            .setContentText(getString(R.string.airplay_ready_detail))
            .setContentIntent(open)
            .setOngoing(true)
            .build();
    }
}
