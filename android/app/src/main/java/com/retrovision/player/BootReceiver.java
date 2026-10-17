package com.retrovision.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are lost on restart, an update or a clock change: set the wake timer again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        WakeAlarm.schedule(c);
    }
}
