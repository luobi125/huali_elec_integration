package com.luobi.hualielec;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/** 桌面小组件：显示余额 / 预计可用天数 / 日均花费，点击打开 App。 */
public class ElecWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        update(context, mgr, ids);
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, ElecWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null && ids.length > 0) {
            update(ctx, mgr, ids);
        }
    }

    static void update(Context ctx, AppWidgetManager mgr, int[] ids) {
        Prefs p = Prefs.load(ctx);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.w_room, p.room);
        rv.setTextViewText(R.id.w_balance, p.cacheBalance);
        rv.setTextViewText(R.id.w_days, p.cacheDays.equals("—") ? "—" : p.cacheDays + " 天");
        rv.setTextViewText(R.id.w_cost, p.cacheCost.equals("—") ? "—" : p.cacheCost + " 元/天");
        rv.setTextViewText(R.id.w_updated, "更新于 " + p.cacheUpdated);

        Intent open = new Intent(ctx, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        rv.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, open, flags));
        mgr.updateAppWidget(ids, rv);
    }
}
