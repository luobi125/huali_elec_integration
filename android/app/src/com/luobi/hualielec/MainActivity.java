package com.luobi.hualielec;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 宿舍电费 —— 直接读华立「智慧微平台」的宿舍电表数据。
 * 支持深浅色、桌面小组件、后台余额提醒。
 */
public class MainActivity extends Activity {

    private static final String TAG = "HualiElec";
    private static final long AUTO_REFRESH_MS = 5 * 60 * 1000L;
    private static final int REQ_NOTIFY = 1001;

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private TextView tRoom, tBalance, tEnergy, tReading, tArrears, tYesterday,
            tMonthKwh, tMonthCost, tDailyCost, tDaysLeft, tStatus, tPrice, tUpdated, tVersion;
    private Button bRefresh, bRoom, bAlert;
    private boolean loading;
    private boolean dark;

    // 调色板（浅色 / 深色）
    private int cBg, cText, cDim, cGood, cWarn;

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    // ---------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.load(getApplicationContext());
        if (prefs.openid.isEmpty()) {
            prefs.openid = HualiApi.generateOpenid();
            prefs.save(getApplicationContext());
        }

        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            cBg = 0xFF111318;
            cText = 0xFFEDEDED;
            cDim = 0xFF9AA0A6;
            cGood = 0xFF4CD07D;
            cWarn = 0xFFFF6B6B;
        } else {
            cBg = 0xFFF6F7F9;
            cText = 0xFF111111;
            cDim = 0xFF888888;
            cGood = 0xFF1A8F3C;
            cWarn = 0xFFCC3333;
        }

        setContentView(buildUi());
        maybeAskNotifyPermission();
        CheckReceiver.schedule(getApplicationContext());

        // 首次安装：先走引导设置
        if (!prefs.onboarded) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        refresh();

        // 自动检查更新（一天最多一次）
        if (Updater.shouldAutoCheck(this)) {
            checkUpdate(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs = Prefs.load(getApplicationContext());
        ui.removeCallbacks(autoRefresh);
        ui.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(autoRefresh);
    }

    private void maybeAskNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    // ---------------------------------------------------------------- ui
    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        tRoom = title(root, prefs.room, 26, cText);
        tUpdated = label(root, "正在读取…", 13, cDim);

        tBalance = title(root, "—", 54, cGood);
        ((LinearLayout.LayoutParams) tBalance.getLayoutParams()).topMargin = dp(12);
        label(root, "电表余额（元）", 13, cDim);

        LinearLayout row1 = row(root);
        tDaysLeft = card(row1, "预计可用", "— 天");
        tDailyCost = card(row1, "日均花费", "— 元/天");

        LinearLayout row2 = row(root);
        tEnergy = card(row2, "剩余电量", "— 度");
        tMonthKwh = card(row2, "本月用电", "— 度");

        LinearLayout row3 = row(root);
        tMonthCost = card(row3, "本月电费", "— 元");
        tYesterday = card(row3, "最近一日", "— 度");

        LinearLayout row4 = row(root);
        tReading = card(row4, "抄表读数", "— 度");
        tArrears = card(row4, "欠费", "— 元");

        tPrice = label(root, "电价 — 元/度", 13, cDim);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(buttons, lp);

        bRefresh = button(buttons, "刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        bRoom = button(buttons, "房号", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRoomDialog();
            }
        });
        bAlert = button(buttons, "提醒设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAlertDialog();
            }
        });

        tStatus = label(root, "", 13, cWarn);
        tStatus.setPadding(0, dp(14), 0, 0);

        // 版本行：点一下手动检查更新
        tVersion = label(root, "当前版本 " + Updater.currentVersion(this) + " · 检查更新", 12, cDim);
        tVersion.setPadding(0, dp(10), 0, 0);
        tVersion.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(true);
            }
        });
        return scroll;
    }

    // ---------------------------------------------------------------- 检查更新
    private void checkUpdate(final boolean manual) {
        if (manual) {
            tVersion.setText("正在检查更新…");
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Updater.Release rel = null;
                String err = null;
                try {
                    rel = Updater.fetchLatest();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final Updater.Release r = rel;
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String cur = Updater.currentVersion(MainActivity.this);
                        if (r == null) {
                            Log.w(TAG, "检查更新失败: " + error);
                            tVersion.setText("当前版本 " + cur + " · 检查更新失败，点此重试");
                            if (manual) {
                                Toast.makeText(MainActivity.this, "检查更新失败：" + error,
                                        Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                        Updater.markChecked(getApplicationContext());
                        int cmp = Updater.compare(r.tag, cur);
                        Log.i(TAG, "检查更新: 本地 " + cur + " / 最新 " + r.tag
                                + " / 新版本=" + (cmp > 0) + " / apk=" + r.apkUrl);
                        tVersion.setText("当前版本 " + cur + " · 已是最新");
                        if (cmp > 0) {
                            tVersion.setText("当前版本 " + cur + " · 发现新版本 " + r.tag);
                            showUpdateDialog(cur, r);
                        } else if (manual) {
                            Toast.makeText(MainActivity.this,
                                    "已是最新版本（" + cur + "）", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void showUpdateDialog(String cur, final Updater.Release rel) {
        String notes = rel.notes == null ? "" : rel.notes.trim();
        if (notes.length() > 600) {
            notes = notes.substring(0, 600) + "…";
        }
        String msg = "当前版本：" + cur + "\n最新版本：" + rel.tag
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("去下载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Updater.openUrl(MainActivity.this,
                                rel.apkUrl.isEmpty() ? rel.pageUrl : rel.apkUrl);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Updater.openUrl(MainActivity.this, rel.pageUrl);
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView title(LinearLayout parent, String text, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        parent.addView(tv);
        return tv;
    }

    private TextView label(LinearLayout parent, String text, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        parent.addView(tv);
        return tv;
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private TextView card(LinearLayout row, String caption, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(12), dp(10), dp(12));
        row.addView(box, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(cText);
        box.addView(t);

        TextView c = new TextView(this);
        c.setText(caption);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.setTextColor(cDim);
        box.addView(c);
        return t;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------------------------------------------------------- dialogs
    private void showRoomDialog() {
        // 直接进引导页（校区 -> 楼栋 -> 房间，都从平台下拉选）
        startActivity(new Intent(this, SetupActivity.class));
    }

    private void showAlertDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        final CheckBox enable = new CheckBox(this);
        enable.setText("后台定时检查并提醒");
        enable.setChecked(prefs.notifyEnabled);
        box.addView(enable);

        final boolean canNotify = CheckReceiver.canNotify(getApplicationContext());
        TextView notice = label(box, canNotify
                ? "系统通知已开启"
                : "⚠ 系统通知被关闭了，点下面的「通知权限」去打开", 13, canNotify ? cDim : cWarn);
        notice.setPadding(0, 0, 0, dp(4));

        final boolean overlay = Island.canShow(getApplicationContext());
        Button overlayBtn = new Button(this);
        overlayBtn.setText(overlay ? "浮岛（悬浮窗）权限已开启" : "开启浮岛权限（显示在其他应用上层）");
        overlayBtn.setEnabled(!overlay);
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    CheckReceiver.openNotificationSettings(MainActivity.this);
                }
            }
        });
        box.addView(overlayBtn);

        Button notifyBtn = new Button(this);
        notifyBtn.setText("通知权限设置");
        notifyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CheckReceiver.openNotificationSettings(MainActivity.this);
            }
        });
        box.addView(notifyBtn);

        label(box, "余额低于（元）", 13, cDim);
        final EditText minBalance = numField(String.valueOf(trim(prefs.minBalance)));
        box.addView(minBalance);

        label(box, "预计可用低于（天）", 13, cDim);
        final EditText minDays = numField(String.valueOf(trim(prefs.minDays)));
        box.addView(minDays);

        label(box, "检查间隔（小时）", 13, cDim);
        final EditText interval = numField(String.valueOf(prefs.intervalHours));
        box.addView(interval);

        new AlertDialog.Builder(this)
                .setTitle("提醒设置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.notifyEnabled = enable.isChecked();
                        prefs.minBalance = parse(minBalance, 20f);
                        prefs.minDays = parse(minDays, 3f);
                        prefs.intervalHours = (int) Math.max(1, parse(interval, 6f));
                        prefs.save(getApplicationContext());
                        CheckReceiver.schedule(getApplicationContext());
                        tStatus.setTextColor(cDim);
                        tStatus.setText("提醒已" + (prefs.notifyEnabled ? "开启" : "关闭")
                                + "：余额 < " + trim(prefs.minBalance) + " 元 或 可用 < "
                                + trim(prefs.minDays) + " 天（每 " + prefs.intervalHours + " 小时检查）");
                    }
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("测试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        CheckReceiver.postTest(getApplicationContext(), "");
                        Toast.makeText(MainActivity.this,
                                CheckReceiver.canNotify(getApplicationContext())
                                        ? "已发送测试通知"
                                        : "通知被系统关闭，请到设置里打开",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private EditText numField(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        return e;
    }

    private static float parse(EditText e, float fallback) {
        try {
            return Float.parseFloat(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    // ---------------------------------------------------------------- refresh
    private void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        bRefresh.setEnabled(false);
        tStatus.setText("");
        final Prefs p = prefs;

        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final HualiApi.Snapshot s = HualiApi.load(p);
                    Log.i(TAG, "OK room=" + p.room + " balance=" + s.meter.balance
                            + " dailyCost=" + s.dailyCost + " daysLeft=" + s.daysLeft
                            + " days=" + s.days.size());
                    HualiApi.saveCache(p, s);
                    p.save(getApplicationContext());
                    p.saveCache(getApplicationContext());
                    ElecWidget.updateAll(getApplicationContext());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            render(s);
                            loading = false;
                            bRefresh.setEnabled(true);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "fetch failed: " + e, e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tStatus.setTextColor(cWarn);
                            tStatus.setText("读取失败：" + e.getMessage());
                            tUpdated.setText("最后更新 " + prefs.cacheUpdated);
                            loading = false;
                            bRefresh.setEnabled(true);
                        }
                    });
                }
            }
        });
    }

    private void render(HualiApi.Snapshot s) {
        tRoom.setText(prefs.room);
        tBalance.setText(fmt(s.meter.balance, 2));
        tBalance.setTextColor(s.meter.balance < prefs.minBalance ? cWarn : cGood);
        tEnergy.setText(fmt(s.meter.price > 0 ? s.meter.balance / s.meter.price : 0, 1) + " 度");
        tReading.setText(fmt(s.meter.reading, 2) + " 度");
        tArrears.setText(fmt(s.meter.arrears, 2) + " 元");
        tPrice.setText("电价 " + fmt(s.meter.price, 2) + " 元/度 · 抄表 " + nz(s.meter.readDate));

        tYesterday.setText(fmt(s.yesterday, 2) + " 度");
        tMonthKwh.setText(fmt(s.monthKwh, 2) + " 度");
        tMonthCost.setText(fmt(s.monthCost, 2) + " 元");
        tDailyCost.setText(fmt(s.dailyCost, 2) + " 元/天");
        tDaysLeft.setText(s.daysLeft > 0 ? fmt(s.daysLeft, 1) + " 天" : "—");
        tDaysLeft.setTextColor(s.lowDays(prefs.minDays) ? cWarn : cText);
        tUpdated.setText("更新于 " + s.updated + " · " + s.days.size() + " 天数据");

        boolean low = s.lowBalance(prefs.minBalance) || s.lowDays(prefs.minDays);
        if (low) {
            tStatus.setTextColor(cWarn);
            tStatus.setText(prefs.notifyEnabled
                    ? "⚠ 已低于提醒阈值，后台会通知你充值"
                    : "⚠ 已低于提醒阈值（提醒未开启）");
        } else {
            tStatus.setText("");
        }
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    private static String fmt(double v, int digits) {
        return String.format(Locale.US, "%." + digits + "f", v);
    }
}
