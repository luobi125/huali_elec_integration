package com.luobi.hualielec;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 华立「智慧微平台」电表接口。
 *
 * 平台是微信 openid 体系；绑定房间不需要密码，同一房间允许多个 openid，
 * 所以 App 生成自己的 openid 绑定房号即可，不影响微信里的绑定。
 */
final class HualiApi {

    static final String BASE = "https://dbwx.hlsywy.com";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36 MicroMessenger/8.0.44";

    static {
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private HualiApi() {
    }

    // ---------------------------------------------------------------- models
    static class Meter {
        String userCode = "";
        String meterAddr = "";
        String readDate = "";
        double balance;
        double price;
        double reading;
        double arrears;
    }

    static class Day {
        String date = "";
        double usage;
    }

    static class Snapshot {
        Meter meter = new Meter();
        final List<Day> days = new ArrayList<>();
        double yesterday;
        double avg7;
        double dailyCost;
        double daysLeft;
        double monthKwh;
        double monthCost;
        String updated = "";

        boolean lowBalance(float minBalance) {
            return meter.balance < minBalance;
        }

        boolean lowDays(float minDays) {
            return dailyCost > 0 && daysLeft < minDays;
        }
    }

    // ---------------------------------------------------------------- public
    static Snapshot load(Prefs p) throws Exception {
        int idx = Prefs.campusIndex(p.campus);
        String memid = Prefs.campusMemid()[idx];
        String op = Prefs.campusOp()[idx];
        if (p.openid == null || p.openid.isEmpty()) {
            p.openid = generateOpenid();
        }

        String show = post("/Web/MWeb/payusereqlist.aspx?_r=show", form(
                "openid", p.openid, "memid", memid, "operatorcode", op, "_m", rand()));
        if (!show.trim().startsWith("{")) {
            bind(memid, op, p.room, p.openid);
            show = post("/Web/MWeb/payusereqlist.aspx?_r=show", form(
                    "openid", p.openid, "memid", memid, "operatorcode", op, "_m", rand()));
        }
        JSONObject root = new JSONObject(show.trim());
        if (!"9999".equals(String.valueOf(root.opt("RtnCode")))) {
            throw new IOException(String.valueOf(root.opt("RtnMsg")));
        }
        JSONArray users = root.optJSONArray("UserDataBody");
        if (users == null || users.length() == 0) {
            throw new IOException("没有绑定到该房号");
        }
        JSONObject user = null;
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            if (p.room.equalsIgnoreCase(u.optString("UserCode"))) {
                user = u;
                break;
            }
        }
        if (user == null) {
            user = users.getJSONObject(0);
        }
        Object rawMeters = user.opt("MeterDataBody");
        JSONArray meters = rawMeters instanceof String
                ? new JSONArray((String) rawMeters) : (JSONArray) rawMeters;
        if (meters == null || meters.length() == 0) {
            throw new IOException("该房号没有电表数据");
        }
        JSONObject meter = meters.getJSONObject(0);

        Snapshot s = new Snapshot();
        s.meter.userCode = user.optString("UserCode", p.room);
        s.meter.meterAddr = meter.optString("MeterAddr", "");
        s.meter.readDate = meter.optString("ReadDate", "");
        s.meter.balance = num(meter.opt("Remnant"));
        s.meter.price = num(meter.opt("Price"));
        s.meter.reading = num(meter.opt("ReadNumber"));
        s.meter.arrears = num(meter.opt("FareMoney"));

        for (int page = 1; page <= 2; page++) {
            String body = post("/Web/MWeb/amountlist.aspx?_r=show", form(
                    "openid", p.openid, "memid", memid, "operatorcode", op,
                    "meteraddr", s.meter.meterAddr, "usercode", s.meter.userCode,
                    "currentPage", String.valueOf(page), "_m", rand()));
            if (!body.trim().startsWith("{")) {
                break;
            }
            JSONObject pageObj = new JSONObject(body.trim());
            Object raw = pageObj.opt("MonthDataBody");
            JSONArray rows = raw instanceof String ? new JSONArray((String) raw) : (JSONArray) raw;
            if (rows == null || rows.length() == 0) {
                break;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                Day d = new Day();
                d.date = r.optString("FreezeMonth", "");
                d.usage = num(r.opt("UseNumber"));
                s.days.add(d);
            }
            int totalPages = (int) num(pageObj.opt("TotalPages"));
            if (totalPages > 0 && page >= totalPages) {
                break;
            }
        }

        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        double sum7 = 0;
        int n7 = 0;
        for (int i = 0; i < s.days.size(); i++) {
            Day d = s.days.get(i);
            if (i == 0) {
                s.yesterday = d.usage;
            }
            if (i < 7) {
                sum7 += d.usage;
                n7++;
            }
            if (d.date.startsWith(month)) {
                s.monthKwh += d.usage;
            }
        }
        s.avg7 = n7 > 0 ? sum7 / n7 : 0;
        s.dailyCost = s.avg7 * s.meter.price;
        s.daysLeft = s.dailyCost > 0 ? s.meter.balance / s.dailyCost : 0;
        s.monthCost = s.monthKwh * s.meter.price;
        s.updated = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
        return s;
    }

    static void saveCache(Prefs p, Snapshot s) {
        p.cacheBalance = String.format(Locale.US, "%.2f", s.meter.balance);
        p.cacheDays = s.daysLeft > 0 ? String.format(Locale.US, "%.1f", s.daysLeft) : "—";
        p.cacheCost = String.format(Locale.US, "%.2f", s.dailyCost);
        p.cacheReadDate = s.meter.readDate;
        p.cacheUpdated = s.updated;
    }

    // ------------------------------------------------------------ 楼栋 / 房间列表
    static class Dept {
        final String id;
        final String label;

        Dept(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 拉取楼栋树，只留叶子节点（就是能选房号的那一层）。 */
    static List<Dept> fetchDepts(Prefs p) throws Exception {
        int idx = Prefs.campusIndex(p.campus);
        String body = post("/api/MiniApp/GetDeptsTree?operatorCode="
                + enc(Prefs.campusOp()[idx]) + "&MemId=" + Prefs.campusMemid()[idx], "");
        JSONObject root = parseMaybeQuoted(body);
        JSONArray tree = root.optJSONArray("DeptsDataBody");
        List<Dept> out = new ArrayList<>();
        walkDepts(tree, out, 0);
        return sortDormFirst(out);
    }

    /** 宿舍/公寓/宿舍楼排前面，教学楼/办公楼排后面。 */
    private static List<Dept> sortDormFirst(List<Dept> src) {
        List<Dept> dorm = new ArrayList<>();
        List<Dept> other = new ArrayList<>();
        for (Dept d : src) {
            String s = d.label;
            if (s.contains("宿舍") || s.contains("公寓") || s.contains("住宿")) {
                dorm.add(d);
            } else {
                other.add(d);
            }
        }
        dorm.addAll(other);
        return dorm;
    }

    private static void walkDepts(JSONArray nodes, List<Dept> out, int depth) {
        if (nodes == null || depth > 5) {
            return;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) {
                continue;
            }
            JSONArray kids = n.optJSONArray("children");
            if (kids == null || kids.length() == 0) {
                String full = n.optString("fullName", n.optString("title"));
                String label = shortPath(full);
                out.add(new Dept(n.optString("id"), label));
            } else {
                walkDepts(kids, out, depth + 1);
            }
        }
    }

    /** "华立学院（广州增城校区）/增城宿舍L区/学生宿舍L9栋/4层宿舍" -> "L区/L9栋/4层宿舍" */
    private static String shortPath(String full) {
        if (full == null) {
            return "";
        }
        String[] parts = full.split("/");
        int from = Math.max(0, parts.length - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(parts[i].trim());
        }
        return sb.toString();
    }

    /** 拉取某个楼栋/楼层下的房号列表。 */
    static List<String> fetchRooms(Prefs p, String deptId) throws Exception {
        int idx = Prefs.campusIndex(p.campus);
        String body = post("/api/MiniApp/GetUserList?operatorCode="
                + enc(Prefs.campusOp()[idx]) + "&DeptID=" + enc(deptId)
                + "&MemId=" + Prefs.campusMemid()[idx], "");
        JSONObject root = parseMaybeQuoted(body);
        JSONArray users = root.optJSONArray("UserDataBody");
        List<String> rooms = new ArrayList<>();
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) {
                    continue;
                }
                String code = u.optString("UserCode", "");
                String name = u.optString("UserName", code);
                rooms.add(code.isEmpty() ? name : code);
            }
        }
        return rooms;
    }

    /** 平台部分接口返回的是“JSON 字符串”（外面多一层引号）。 */
    private static JSONObject parseMaybeQuoted(String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new IOException("接口返回为空");
        }
        if (t.startsWith("\"")) {
            t = new JSONTokener(t).nextValue().toString();
        }
        JSONObject obj = new JSONObject(t);
        if (!"9999".equals(String.valueOf(obj.opt("RtnCode")))) {
            throw new IOException(String.valueOf(obj.opt("RtnMsg")));
        }
        return obj;
    }

    // ---------------------------------------------------------------- http
    private static void bind(String memid, String op, String room, String openid) throws Exception {
        String path = "/Web/MWeb/login_HN.aspx?memid=" + memid
                + "&openid=" + enc(openid) + "&operatorcode=" + op;
        String html = get(path);
        String body = form(
                "__VIEWSTATE", hidden(html, "__VIEWSTATE"),
                "__VIEWSTATEGENERATOR", hidden(html, "__VIEWSTATEGENERATOR"),
                "__EVENTVALIDATION", hidden(html, "__EVENTVALIDATION"),
                "citySel", room,
                "dept", "",
                "ddlRoom", room,
                "btnSubmit", "绑",
                "userid", "",
                "memid", memid,
                "openid", openid,
                "operatorcode", op,
                "hidThirdUrl", "http://127.0.0.1:8051/api/Meters/",
                "hidUserCode", room);
        String resp = post(path, body);
        if (resp.contains("绑定成功") || resp.contains("已绑定") || resp.contains("不能重复绑定")) {
            return;
        }
        if (resp.contains("未查询到该用户")) {
            throw new IOException("未查询到房号 " + room + "，请检查楼栋");
        }
        throw new IOException("绑定失败");
    }

    private static String get(String path) throws IOException {
        HttpURLConnection c = open(path, "GET");
        try {
            int code = c.getResponseCode();
            return read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String post(String path, String body) throws IOException {
        HttpURLConnection c = open(path, "POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setDoOutput(true);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            return read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects("GET".equals(method));
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", BASE + "/Web/MWeb/index.aspx");
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        return c;
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        return sb.toString();
    }

    private static String hidden(String html, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"" + name + "\"[^>]*?value=\"([^\"]*)\"")
                .matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(kv[i]).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    private static String rand() {
        return String.format(Locale.US, "%.16f", Math.random());
    }

    static String generateOpenid() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder("o");
        for (int i = 0; i < 27; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static double num(Object o) {
        if (o == null || o == JSONObject.NULL) {
            return 0;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
