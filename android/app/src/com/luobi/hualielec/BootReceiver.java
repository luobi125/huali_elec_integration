package com.luobi.hualielec;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后重新安排定时检查（AlarmManager 的重复闹钟重启后会丢）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            CheckReceiver.schedule(context);
            ElecWidget.updateAll(context);
        }
    }
}
