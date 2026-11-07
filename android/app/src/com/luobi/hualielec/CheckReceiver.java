package com.luobi.hualielec;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * 后台定时检查：拉一次数据 → 写缓存 → 更新小组件 → 余额/可用天数低于阈值就发通知。
 */
public class CheckReceiver extends BroadcastReceiver {

    static final String ACTION_CHECK = "com.luobi.hualielec.CHECK";
    static final String ACTION_TEST = "com.luobi.hualielec.TEST";
    static final String CHANNEL = "elec_alert_v3";
    static final int NOTIFY_ID = 418;
    private static final String PKG = "com.luobi.hualielec";
    private static final String TAG = "HualiElec";

    static void schedule(Context ctx) {
        Prefs p = Prefs.load(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(ctx);
        am.cancel(pi);
        if (!p.notifyEnabled) {
            Log.i(TAG, "schedule: 通知已关闭，取消定时检查");
            return;
        }
        long interval = Math.max(1, p.intervalHours) * 60L * 60L * 1000L;
        am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 60_000L,
                interval,
                pi);
        Log.i(TAG, "schedule: 每 " + p.intervalHours + " 小时检查一次");
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, CheckReceiver.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, 0, i, flags);
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TEST.equals(action)) {
            postTest(context.getApplicationContext());
            return;
        }
        if (!ACTION_CHECK.equals(action)) {
            return;
        }
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(app);
                } catch (Throwable t) {
                    Log.e(TAG, "后台检查失败: " + t, t);
                } finally {
                    pr.finish();
                }
            }
        }, "elec-check").start();
    }

    private void check(Context ctx) {
        Prefs p = Prefs.load(ctx);
        HualiApi.Snapshot s;
        try {
            s = HualiApi.load(p);
        } catch (Exception e) {
            Log.e(TAG, "后台检查取数失败: " + e, e);
            return;
        }
        p.save(ctx); // openid 可能是新建的
        HualiApi.saveCache(p, s);
        p.saveCache(ctx);
        ElecWidget.updateAll(ctx);

        Log.i(TAG, "后台检查 OK balance=" + s.meter.balance
                + " daysLeft=" + s.daysLeft + " dailyCost=" + s.dailyCost);

        boolean lowBalance = s.lowBalance(p.minBalance);
        boolean lowDays = s.lowDays(p.minDays);
        if (!lowBalance && !lowDays) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFY_ID);
            }
            return;
        }
        notifyLow(ctx, p, s, lowBalance, lowDays);
    }

    private static void notifyLow(Context ctx, Prefs p, HualiApi.Snapshot s,
                                  boolean lowBalance, boolean lowDays) {
        notifyLow(ctx, p, s, lowBalance, lowDays, NOTIFY_ID);
    }

    private static void notifyLow(Context ctx, Prefs p, HualiApi.Snapshot s,
                                  boolean lowBalance, boolean lowDays, int notifyId) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "电费提醒（弹窗）", 5);  // 5 = IMPORTANCE_MAX，MIUI 上更容易弹横幅
            ch.setDescription("余额或预计可用天数过低时弹窗提醒充值");
            ch.enableVibration(true);
            ch.enableLights(true);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("余额 ").append(String.format(java.util.Locale.US, "%.2f", s.meter.balance)).append(" 元");
        if (s.dailyCost > 0) {
            sb.append("，日均 ").append(String.format(java.util.Locale.US, "%.2f", s.dailyCost)).append(" 元/天");
            sb.append("，预计还能用 ")
                    .append(String.format(java.util.Locale.US, "%.1f", s.daysLeft)).append(" 天");
        }
        if (lowBalance && lowDays) {
            sb.append("（余额和可用天数都低于设定值）");
        } else if (lowBalance) {
            sb.append("（余额低于 ").append(trim(p.minBalance)).append(" 元）");
        } else {
            sb.append("（可用天数低于 ").append(trim(p.minDays)).append(" 天）");
        }

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("⚡ " + p.room + " 电费快用完了")
                .setContentText(sb.toString())
                .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= 26) {
            b.setPriority(Notification.PRIORITY_HIGH);
        } else {
            b.setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                    .setOnlyAlertOnce(true);
        }
        Notification notification = b.build();

        // 小米澎湃 HyperOS 焦点通知 / 灵动岛适配
        String ticker = String.format(java.util.Locale.US, "余额 %.2f 元 · 可用 %.1f 天",
                s.meter.balance, s.daysLeft);
        applyHyperOsFocus(notification, p.room + " 电费告警", ticker, sb.toString());

        nm.notify(notifyId, notification);
        Log.i(TAG, "已发通知: " + sb);

        // 能画悬浮窗的话，再弹一个自绘“超级岛”胶囊（不依赖小米白名单）
        Island.show(ctx, p.room,
                String.format(java.util.Locale.US, "%.2f", s.meter.balance),
                String.format(java.util.Locale.US, "%.1f", s.daysLeft));
    }

    /** 设备是不是小米 MIUI / 澎湃 HyperOS。 */
    private static boolean isMiuiFamily() {
        try {
            Class<?> prop = Class.forName("android.os.SystemProperties");
            Object v = prop.getMethod("get", String.class).invoke(null, "ro.miui.ui.version.name");
            if (v != null && !String.valueOf(v).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 非 MIUI 没有这个系统属性
        }
        return "xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * 给通知补上澎湃「焦点通知」需要的 extras（key 来自 MiuiSystemUI 的常量表）。
     * 非小米设备直接跳过，其它系统不会因此出错。
     */
    private static void applyHyperOsFocus(Notification n, String title, String ticker, String content) {
        if (n == null || !isMiuiFamily()) {
            return;
        }
        if (n.extras == null) {
            n.extras = new Bundle();
        }
        n.extras.putBoolean("miui.focus.isFocus", true);
        n.extras.putBoolean("miui.focus.enableAlert", true);
        n.extras.putBoolean("miui.focus.isPromoted", true);
        n.extras.putString("miui.focus.ticker", ticker);
        n.extras.putBoolean("miui.focus.tickerAutoClose", true);
        // 系统判定“是否焦点通知”时还会看有没有可用的 RemoteViews
        try {
            android.widget.RemoteViews rv = new android.widget.RemoteViews(
                    PKG, com.luobi.hualielec.R.layout.widget);
            rv.setTextViewText(com.luobi.hualielec.R.id.w_room, title);
            rv.setTextViewText(com.luobi.hualielec.R.id.w_balance, ticker);
            n.extras.putParcelable("miui.focus.rv", rv);
        } catch (Throwable t) {
            Log.w(TAG, "附加 miui.focus.rv 失败: " + t);
        }
        Log.i(TAG, "焦点通知 extras 已附加: " + ticker);
    }

    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    static boolean hasPostPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean canNotify(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return hasPostPermission(ctx) && (nm == null || nm.areNotificationsEnabled());
    }

    /** 跳到本应用的通知设置页（MIUI 上也能直接跳到通知管理）。 */
    static void openNotificationSettings(Context ctx) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Log.w(TAG, "打不开通知设置: " + e);
            }
        }
    }

    /** 设置页里的「测试」按钮：不联网，直接发一条样例提醒（顺便验证澎湃焦点通知）。 */
    static void postTest(Context ctx) {
        Prefs p = Prefs.load(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancelAll();   // 先清掉旧通知，保证系统重新走一遍通知/焦点通知流程
            Log.i(TAG, "测试通知：系统通知开关 enabled=" + nm.areNotificationsEnabled()
                    + " channel重要性按默认；已授权 POST_NOTIFICATIONS=" + hasPostPermission(ctx));
            if (!nm.areNotificationsEnabled()) {
                Log.w(TAG, "测试通知被系统拦住了：应用通知被关闭，请到系统设置里打开");
            }
        }
        HualiApi.Snapshot s = new HualiApi.Snapshot();
        s.meter.balance = p.minBalance > 0 ? p.minBalance - 5 : 15;
        s.meter.price = 0.62;
        s.dailyCost = 12.06;
        s.daysLeft = 2.4;
        s.updated = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date());
        try {
            notifyLow(ctx, p, s, true, true,
                    NOTIFY_ID + 1 + (int) (System.currentTimeMillis() % 900));
            Log.i(TAG, "测试通知已发送");
        } catch (Throwable t) {
            Log.e(TAG, "测试通知失败: " + t, t);
        }
    }
}
