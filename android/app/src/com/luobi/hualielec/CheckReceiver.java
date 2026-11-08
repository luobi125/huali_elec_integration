package com.luobi.hualielec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

/**
 * 后台定时检查：拉一次数据 → 写缓存 → 更新小组件 → 余额/可用天数低于阈值就发通知。
 *
 * 通知同时尝试三种“变大号/上岛”的途径（按支持情况自动降级）：
 * 1. Android 16 Live Updates：setShortCriticalText + requestPromotedOngoing（ColorOS16/MagicOS10/HyperOS3.1+ 会画进岛）
 * 2. 澎湃焦点通知：miui.focus.param（JSON 模板）+ miui.focus.pics（图标），由 SystemUI 自己渲染
 * 3. 自绘悬浮胶囊（Island.java），任何设备都能用
 *
 * 焦点通知模板格式参考：Xiaomi HyperOS 文档 pId=2131 / 限制 pId=2146，
 * 以及 lxsfful/dsh-go 的 XiaomiFocus.kt 实现。
 */
public class CheckReceiver extends BroadcastReceiver {

    static final String ACTION_CHECK = "com.luobi.hualielec.CHECK";
    static final String ACTION_TEST = "com.luobi.hualielec.TEST";
    static final String CHANNEL = "elec_alert_v3";
    static final int NOTIFY_ID = 418;
    private static final String PKG = "com.luobi.hualielec";
    private static final String TAG = "HualiElec";

    // 焦点通知相关常量
    private static final String EXTRA_PARAM = "miui.focus.param";
    private static final String EXTRA_PICS = "miui.focus.pics";
    private static final String PIC_ICON = "miui.focus.pic_icon";
    private static final String FOCUS_PROVIDER = "content://miui.statusbar.notification.public";
    private static final String PROTOCOL_SETTING = "notification_focus_protocol";
    private static final int PARAM_MAX_BYTES = 3072;

    static void schedule(Context ctx) {
        Prefs p = Prefs.load(ctx);
        AlarmManagerBridge.schedule(ctx, p);
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
            String variant = intent == null ? "" : intent.getStringExtra("variant");
            postTest(context.getApplicationContext(), variant == null ? "" : variant);
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

        String balanceText = String.format(java.util.Locale.US, "%.2f", s.meter.balance);
        String daysText = s.daysLeft > 0
                ? String.format(java.util.Locale.US, "%.1f", s.daysLeft) : "—";
        String costText = String.format(java.util.Locale.US, "%.2f", s.dailyCost);

        StringBuilder sb = new StringBuilder();
        sb.append("余额 ").append(balanceText).append(" 元");
        if (s.dailyCost > 0) {
            sb.append("，日均 ").append(costText).append(" 元/天");
            sb.append("，预计还能用 ").append(daysText).append(" 天");
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

        // Android 16 Live Updates：状态栏 chip / 锁屏卡片；HyperOS 3.1+、ColorOS16 等会画进岛
        applyLiveUpdate(b, balanceText + "元");

        Notification notification = b.build();

        // 澎湃焦点通知（JSON 模板 + 图标，不需要 RemoteViews）
        String islandTitle = "宿舍电费 " + p.room;
        String islandContent = "余额 " + balanceText + " 元 · 可用 " + daysText + " 天";
        applyHyperOsFocus(ctx, notification, islandTitle,
                daysText + "天", balanceText + "元", islandContent);

        nm.notify(notifyId, notification);
        Log.i(TAG, "已发通知: " + sb);

        // 系统不给上岛（非小米 / 未放行）时，用自绘胶囊兜底
        if (isMiuiFamily() && canShowFocus(ctx)) {
            Log.i(TAG, "原生超级岛已接管，跳过自绘胶囊");
        } else {
            Island.show(ctx, p.room, balanceText, daysText);
        }
    }

    /** Android 16 的 Live Updates：短文本 + 请求置顶（反射调用，低版本自动跳过）。 */
    private static void applyLiveUpdate(Notification.Builder b, String shortText) {
        if (Build.VERSION.SDK_INT < 36) {
            return;
        }
        try {
            java.lang.reflect.Method m =
                    Notification.Builder.class.getMethod("setShortCriticalText", CharSequence.class);
            m.invoke(b, shortText);
            Log.i(TAG, "Live Update: setShortCriticalText=" + shortText);
        } catch (Throwable t) {
            Log.w(TAG, "setShortCriticalText 不可用: " + t);
        }
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            b.addExtras(extras);
        } catch (Throwable t) {
            Log.w(TAG, "requestPromotedOngoing 失败: " + t);
        }
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

    /** HyperOS 焦点通知协议版本：1/2/3，其它为 0。 */
    private static int focusProtocol(Context ctx) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), PROTOCOL_SETTING, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** SystemUI 是否允许本应用发焦点通知（binder 调用到 SystemUI）。 */
    private static boolean canShowFocus(Context ctx) {
        try {
            Bundle extras = new Bundle();
            extras.putString("package", ctx.getPackageName());
            Bundle res = ctx.getContentResolver()
                    .call(Uri.parse(FOCUS_PROVIDER), "canShowFocus", null, extras);
            return res != null && res.getBoolean("canShowFocus", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 给通知补上澎湃「焦点通知 / 超级岛」需要的 extras。
     *
     * 关键：只用 JSON 模板 + 图标，**不要**塞 miui.focus.rv（RemoteViews）。
     * 塞 RemoteViews 会让 SystemUI 走 inflate 流程，那一步有签名白名单校验（会 onAuthFailed）。
     */
    private static void applyHyperOsFocus(Context ctx, Notification n,
                                          String title, String word, String rightWord, String content) {
        if (n == null || !isMiuiFamily()) {
            return;
        }
        int protocol = focusProtocol(ctx);
        boolean allowed = canShowFocus(ctx);
        Log.i(TAG, "焦点通知: protocol=" + protocol + " canShowFocus=" + allowed);
        if (!allowed) {
            return;
        }
        if (n.extras == null) {
            n.extras = new Bundle();
        }
        try {
            String json = focusTemplate(word, rightWord, title, content);
            Icon icon = Icon.createWithResource(ctx, com.luobi.hualielec.R.mipmap.ic_launcher);
            Bundle pics = new Bundle();
            pics.putParcelable(PIC_ICON, icon);

            n.extras.putString(EXTRA_PARAM, json);
            n.extras.putBundle(EXTRA_PICS, pics);
            // 老字段保留，不影响模板渲染
            n.extras.putBoolean("miui.focus.isFocus", true);
            n.extras.putBoolean("miui.focus.enableAlert", true);
            n.extras.putString("miui.focus.ticker", word + " · " + title);
            n.extras.putBoolean("miui.focus.tickerAutoClose", true);
            Log.i(TAG, "焦点通知模板已附加(" + json.getBytes("UTF-8").length + " bytes): " + word);
        } catch (Throwable t) {
            Log.w(TAG, "附加焦点通知失败: " + t);
        }
    }

    /** 按 HyperOS 文档的 param_v2 模板生成 JSON（超长会截断到 3072 字节内）。
     *  小岛：左 imageTextInfoLeft = 可用天数（带应用图标），右 imageTextInfoRight = 余额（type 必须是 2）。 */
    private static String focusTemplate(String leftWord, String rightWord, String title,
                                        String content) throws Exception {
        String lw = leftWord.length() > 6 ? leftWord.substring(0, 6) : leftWord;
        String rw = rightWord.length() > 6 ? rightWord.substring(0, 6) : rightWord;
        String t = title;
        String c = content;
        while (true) {
            // 结构参考 SystemUI 里的 parseOS2DefaultIslandData：
            // 系统自己构建时只写 bigIslandArea（imageTextInfoLeft + imageTextInfoRight），不写 smallIslandArea。
            JSONObject pic = new JSONObject().put("type", 1).put("pic", PIC_ICON);
            JSONObject leftInfo = new JSONObject()
                    .put("type", 1)
                    .put("picInfo", pic)
                    .put("textInfo", new JSONObject().put("title", lw));
            // SystemUI 的 IslandTemplateFactory.chooseModule() 里，area_right 分支只认
            // type = 2 / 3 / 4；写 1 或 5 会直接落到 generic 分支，右侧就永远空白（踩过的坑）。
            JSONObject rightInfo = new JSONObject()
                    .put("type", 2)
                    .put("textInfo", new JSONObject().put("title", rw));
            JSONObject bigArea = new JSONObject()
                    .put("imageTextInfoLeft", leftInfo)
                    .put("imageTextInfoRight", rightInfo);
            JSONObject island = new JSONObject()
                    .put("islandProperty", 1)
                    .put("bigIslandArea", bigArea);
            JSONObject baseInfo = new JSONObject()
                    .put("type", 2)
                    .put("title", t)
                    .put("content", c);
            JSONObject v2 = new JSONObject()
                    .put("protocol", 1)
                    .put("business", "huali_elec_bill")
                    .put("updatable", true)
                    .put("enableFloat", false)
                    .put("ticker", lw + " · " + rw)
                    .put("aodTitle", lw)
                    .put("param_island", island)
                    .put("baseInfo", baseInfo);
            String json = new JSONObject().put("param_v2", v2).toString();

            if (json.getBytes("UTF-8").length <= PARAM_MAX_BYTES || (t.length() <= 6 && c.isEmpty())) {
                return json;
            }
            if (!c.isEmpty()) {
                c = c.substring(0, c.length() / 2);
            } else {
                t = t.substring(0, t.length() / 2);
            }
        }
    }

    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static void postTest(Context ctx) {
        postTest(ctx, "");
    }

    /** 设置页里的「测试」按钮：不联网，直接发一条样例提醒（顺便验证澎湃焦点通知）。 */
    static void postTest(Context ctx, String variant) {
        Prefs p = Prefs.load(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancelAll();   // 先清掉旧通知，保证系统重新走一遍通知/焦点通知流程
            Log.i(TAG, "测试通知：系统通知开关 enabled=" + nm.areNotificationsEnabled()
                    + "；已授权 POST_NOTIFICATIONS=" + hasPostPermission(ctx));
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
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Log.w(TAG, "打不开通知设置: " + e);
            }
        }
    }

    /** AlarmManager 定时排程（抽出来避免和 Receiver 主体混在一起）。 */
    private static final class AlarmManagerBridge {
        static void schedule(Context ctx, Prefs p) {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
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
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 60_000L, interval, pi);
            Log.i(TAG, "schedule: 每 " + p.intervalHours + " 小时检查一次");
        }
    }
}
