package com.luobi.hualielec;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 检查更新：从 GitHub Releases 拉最新版本，和本机 versionName 比较。
 * 更新源：https://github.com/luobi125/huali_elec_integration
 */
final class Updater {

    private static final String TAG = "HualiElec";
    static final String RELEASES_API =
            "https://api.github.com/repos/luobi125/huali_elec_integration/releases/latest";
    static final String RELEASES_PAGE =
            "https://github.com/luobi125/huali_elec_integration/releases/latest";

    private Updater() {
    }

    static class Release {
        String tag = "";
        String apkUrl = "";
        String pageUrl = RELEASES_PAGE;
        String notes = "";
        String published = "";
    }

    static String currentVersion(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName == null ? "0" : info.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    static Release fetchLatest() throws Exception {
        String body = httpGet(RELEASES_API);
        JSONObject root = new JSONObject(body);
        Release rel = new Release();
        rel.tag = root.optString("tag_name", "").trim();
        rel.pageUrl = root.optString("html_url", RELEASES_PAGE);
        rel.notes = root.optString("body", "");
        rel.published = root.optString("published_at", "");
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String name = a.optString("name", "");
                if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                    rel.apkUrl = a.optString("browser_download_url", "");
                    break;
                }
            }
        }
        return rel;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "HualiElec-Android");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub 返回 HTTP " + code);
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** 把 "v1.2.3" / "1.2.3-beta" 转成可比较的数字数组。 */
    private static int[] parse(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.US);
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!(Character.isDigit(ch) || ch == '.')) {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** >0 表示 remote 比 local 新 */
    static int compare(String remote, String local) {
        int[] r = parse(remote);
        int[] l = parse(local);
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) {
                return r[i] - l[i];
            }
        }
        return 0;
    }

    /**
     * 自动检查频率：一天最多一次（避免烦人，也避免打爆 GitHub 匿名 API 限额）。
     */
    static boolean shouldAutoCheck(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        long last = sp.getLong("update_checked_at", 0L);
        return System.currentTimeMillis() - last > 24L * 3600L * 1000L;
    }

    static void markChecked(Context ctx) {
        ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                .edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
    }

    static void openUrl(Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "打不开链接: " + url + " " + e);
        }
    }
}
