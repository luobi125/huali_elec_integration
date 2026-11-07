package com.luobi.hualielec;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 自绘“超级岛”：在状态栏下方浮出一个胶囊，显示余额 / 预计可用天数，几秒后自动收起，点一下进 App。
 *
 * 用 TYPE_APPLICATION_OVERLAY 直接挂 View（不走 Service），避开后台启动服务的限制。
 * 小米的焦点通知/灵动岛是签名白名单制（SignatureChecker 会比对小米云控白名单），
 * 侧载 App 过不了校验，所以这里自己画一个。
 */
final class Island {

    private static final String TAG = "HualiElec";
    private static final long SHOW_MS = 6500L;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static View current;
    private static WindowManager currentWm;
    private static Runnable pendingRemove;

    private Island() {
    }

    static boolean canShow(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    static void show(Context ctx, String room, String balance, String days) {
        try {
            // MIUI 上 Settings.canDrawOverlays() 有时与实际授权不一致，
            // 所以不拦，直接试，失败会捕获并记日志
            showInner(ctx.getApplicationContext(), room, balance, days);
        } catch (Throwable t) {
            Log.w(TAG, "浮岛显示失败: " + t);
        }
    }

    private static int dp(Context ctx, float v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }

    private static void showInner(final Context ctx, String room, String balance, String days) {
        hide();

        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(ctx, 18), dp(ctx, 9), dp(ctx, 20), dp(ctx, 9));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6000000"));
        bg.setCornerRadius(dp(ctx, 60));
        box.setBackground(bg);

        TextView icon = new TextView(ctx);
        icon.setText("⚡");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.addView(icon);

        TextView main = new TextView(ctx);
        main.setText(balance + " 元");
        main.setTextColor(Color.WHITE);
        main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        main.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 8);
        box.addView(main, lp);

        TextView sub = new TextView(ctx);
        sub.setText(" · 可用 " + days + " 天");
        sub.setTextColor(Color.parseColor("#B0FFFFFF"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(sub);

        TextView roomView = new TextView(ctx);
        roomView.setText("  " + room);
        roomView.setTextColor(Color.parseColor("#80FFFFFF"));
        roomView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.addView(roomView);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(ctx, 56);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ctx.startActivity(new Intent(ctx, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (Throwable ignored) {
                    // ignore
                }
                hide();
            }
        });

        wm.addView(box, params);
        current = box;
        currentWm = wm;

        box.setAlpha(0f);
        box.setScaleX(0.88f);
        box.setScaleY(0.88f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        Log.i(TAG, "浮岛已显示: " + balance + " 元 / " + days + " 天");

        pendingRemove = new Runnable() {
            @Override
            public void run() {
                hide();
            }
        };
        UI.postDelayed(pendingRemove, SHOW_MS);
    }

    static void hide() {
        if (pendingRemove != null) {
            UI.removeCallbacks(pendingRemove);
            pendingRemove = null;
        }
        final View v = current;
        final WindowManager wm = currentWm;
        current = null;
        currentWm = null;
        if (v == null || wm == null) {
            return;
        }
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable ignored) {
            // 已经被系统移除
        }
    }
}
