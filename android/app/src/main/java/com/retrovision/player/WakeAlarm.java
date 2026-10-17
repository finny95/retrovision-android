package com.retrovision.player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;

/** The wake timer: turns the TV box on at a set time and tunes a channel. */
final class WakeAlarm {
    static final String ACTION_WAKE = "com.retrovision.player.WAKE";
    static final String EXTRA_WAKE = "rv_wake";
    static final int NOTIFY_ID = 4401;

    private WakeAlarm() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("retrovision", Context.MODE_PRIVATE);
    }

    private static boolean dayAllowed(String days, int dow) {
        boolean weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY;
        if ("weekdays".equals(days)) return !weekend;
        if ("weekends".equals(days)) return weekend;
        return true;
    }

    /** Next time the alarm should go off, or -1 when it's off. */
    static long nextTrigger(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("wake_on", false)) return -1;
        int h = p.getInt("wake_h", 7);
        int m = p.getInt("wake_m", 0);
        String days = p.getString("wake_days", "all");
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (cal.getTimeInMillis() > now + 1000 && dayAllowed(days, cal.get(Calendar.DAY_OF_WEEK))) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return -1;
    }

    static Intent launchIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EXTRA_WAKE, prefs(c).getInt("wake_channel", 0));
        return i;
    }

    private static PendingIntent firePi(Context c) {
        Intent i = new Intent(c, WakeReceiver.class).setAction(ACTION_WAKE);
        return PendingIntent.getBroadcast(c, 41, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** (Re)schedule from the saved settings. Returns whether the alarm is exact. */
    static boolean schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        PendingIntent fire = firePi(c);
        am.cancel(fire);
        long at = nextTrigger(c);
        if (at < 0) return false;
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exact) {
            PendingIntent show = PendingIntent.getActivity(c, 42, launchIntent(c), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        }
        return exact;
    }
}
