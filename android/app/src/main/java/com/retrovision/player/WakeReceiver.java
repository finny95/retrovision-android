package com.retrovision.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * The wake timer went off: wake the screen and open the app on the chosen channel.
 * Apps can't simply start themselves from the background on newer Android, so it
 * uses a full-screen notification (how alarm clocks do it), or starts directly
 * when "display over other apps" is allowed.
 */
public class WakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!WakeAlarm.ACTION_WAKE.equals(intent.getAction())) return;
        // Keep the CPU up while we start things. Don't switch the screen on here:
        // the full-screen notification only opens the app while the screen is off
        // (the app switches the screen on itself as it opens).
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retrovision:wake").acquire(15000);
        } catch (Exception ignored) {
            // carry on
        }

        Intent launch = WakeAlarm.launchIntent(c);
        boolean started = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(launch);
                started = true;
            } catch (Exception ignored) {
                started = false;
            }
        }
        if (!started) notifyFullScreen(c, launch);
        WakeAlarm.schedule(c);
    }

    private static void notifyFullScreen(Context c, Intent launch) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String id = "wake";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(id, "Wake timer", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Turns the TV on at the time you set");
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(c, 43, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(c, id) : new Notification.Builder(c);
        b.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("RetroVision")
            .setContentText("Good morning. Your channel is on.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTimeoutAfter(120000);
        try {
            nm.notify(WakeAlarm.NOTIFY_ID, b.build());
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }
}
