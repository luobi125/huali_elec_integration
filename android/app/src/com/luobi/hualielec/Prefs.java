package com.luobi.hualielec;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置 + 上一次成功拉取的缓存（供小组件/通知使用）。 */
final class Prefs {

    static final String FILE = "huali_elec";

    String campus = "zengcheng";
    String room = "L9-418";
    String openid = "";

    boolean notifyEnabled = true;
    float minBalance = 20f;
    float minDays = 3f;
    int intervalHours = 6;
    boolean onboarded = false;

    // 缓存
    String cacheBalance = "—";
    String cacheDays = "—";
    String cacheCost = "—";
    String cacheReadDate = "—";
    String cacheUpdated = "—";

    static String[] campusKeys() {
        return new String[]{"zengcheng", "yunfu", "jiangmen_bachelor", "jiangmen_technician"};
    }

    static String[] campusNames() {
        return new String[]{"增城校区", "云浮校区", "江门本科校区", "江门技师校区"};
    }

    static String[] campusMemid() {
        return new String[]{"208", "250", "208", "179"};
    }

    static String[] campusOp() {
        return new String[]{"ZCSSDB", "YFSSDB", "JMBKSSDB", "JMJSSSDB"};
    }

    static int campusIndex(String key) {
        String[] keys = campusKeys();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return 0;
    }

    static Prefs load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.campus = sp.getString("campus", p.campus);
        p.room = sp.getString("room", p.room);
        p.openid = sp.getString("openid", "");
        p.notifyEnabled = sp.getBoolean("notify", true);
        p.minBalance = sp.getFloat("min_balance", 20f);
        p.minDays = sp.getFloat("min_days", 3f);
        p.intervalHours = sp.getInt("interval", 6);
        p.onboarded = sp.getBoolean("onboarded", false);
        p.cacheBalance = sp.getString("c_balance", "—");
        p.cacheDays = sp.getString("c_days", "—");
        p.cacheCost = sp.getString("c_cost", "—");
        p.cacheReadDate = sp.getString("c_read_date", "—");
        p.cacheUpdated = sp.getString("c_updated", "—");
        return p;
    }

    void save(Context ctx) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("campus", campus)
                .putString("room", room)
                .putString("openid", openid)
                .putBoolean("notify", notifyEnabled)
                .putFloat("min_balance", minBalance)
                .putFloat("min_days", minDays)
                .putInt("interval", intervalHours)
                .putBoolean("onboarded", onboarded)
                .apply();
    }

    void saveCache(Context ctx) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("c_balance", cacheBalance)
                .putString("c_days", cacheDays)
                .putString("c_cost", cacheCost)
                .putString("c_read_date", cacheReadDate)
                .putString("c_updated", cacheUpdated)
                .apply();
    }

    String describe() {
        return campusNames()[campusIndex(campus)] + " " + room;
    }
}
