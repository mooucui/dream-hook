package com.todesk.dreamhook;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 到梦空间接口客户端 —— 跑在到梦空间自己的进程里，用的是它的网络环境。
 *
 * 登录态优先级：
 *   1) 抓到的 / 从存储捞出来的 uid+token
 *   2) 实在没有 → 用界面上填的账号密码自己登（DES 加密，不经过第三方）
 */
public class DmClient {

    public static final String HOST = "https://appdmkj.5idream.net";

    public static class ApiEx extends Exception {
        public final String code;

        public ApiEx(String code, String msg) {
            super("[" + code + "] " + msg);
            this.code = code;
        }
    }

    public String uid;
    public String token;
    public final String ua;
    public final String jpush;
    public final String version;

    public DmClient() {
        uid = Bridge.q("uid").trim();
        token = Bridge.q("token").trim();
        ua = Bridge.q("ua").trim();
        jpush = Bridge.q("jpush").trim();
        String v = Bridge.q("appVersion").trim();
        version = v.isEmpty() ? "5.0.0" : v;
    }

    public boolean ready() {
        return !uid.isEmpty() && !token.isEmpty() && !ua.isEmpty() && !jpush.isEmpty();
    }

    public boolean hasSession() {
        return !uid.isEmpty() && !token.isEmpty();
    }

    public String missing() {
        StringBuilder sb = new StringBuilder();
        if (uid.isEmpty()) sb.append("uid ");
        if (token.isEmpty()) sb.append("token ");
        if (ua.isEmpty()) sb.append("设备指纹 ");
        if (jpush.isEmpty()) sb.append("jPushId ");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ 登录

    /**
     * 账号密码登录。成功后把 uid/token 回写配置，后续 new DmClient() 就能直接用。
     * 注意：登录接口不能带 timestamp。
     */
    public JSONObject login(String account, String password) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("uid", "");
        p.put("token", "");
        p.put("account", account);
        p.put("pwd", DmCrypto.desPassword(password));
        p.put("version", version);
        p.put("signToken", DmCrypto.signToken(p));

        String body = "d=" + URLEncoder.encode(DmCrypto.makeD(p), "UTF-8");
        String raw = postRaw(HOST + "/v2/login/phone", body);
        JSONObject root = new JSONObject(raw);
        String code = root.optString("code", "");
        if (!"100".equals(code)) {
            throw new ApiEx(code, errMsg(root));
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new ApiEx("LOGIN", "登录返回没有 data");
        }
        uid = data.optString("uid", "");
        token = data.optString("token", "");
        if (uid.isEmpty() || token.isEmpty()) {
            throw new ApiEx("LOGIN", "登录返回没有 uid/token");
        }
        Bridge.set("uid", uid);
        Bridge.set("token", token);
        return data;
    }

    // ------------------------------------------------------------ 接口

    public void submit(String activityId) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("activityId", activityId);
        p.put("data", "[]");
        p.put("remark", "");
        p.put("useNumber", "0");
        p.put("jPushId", jpush);
        post("/v2/signup/submit", p, true, true);
    }

    public Object detail(String activityId) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("activityId", activityId);
        return post("/v2/activity/detail", p, true, false);
    }

    public Object activities(int page) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("page", String.valueOf(page));
        return post("/v2/activity/activities", p, true, true);
    }

    public Object signupInfo(String activityId) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("activityId", activityId);
        return post("/v2/signup/info", p, true, false);
    }

    // ------------------------------------------------------------ 验证码相关

    /**
     * 获取验证码图片（返回字节数组）
     * 实际端点需根据抓包确认，常见：/v2/captcha/get、/v2/captcha/image、/v1/captcha
     */
    public byte[] getCaptcha() throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("timestamp", String.valueOf(System.currentTimeMillis()));
        p.put("signToken", DmCrypto.signToken(p));

        String body = "d=" + URLEncoder.encode(DmCrypto.makeD(p), "UTF-8");
        return postRawBytes(HOST + "/v2/captcha/get", body);
    }

    /**
     * 提交验证码验证
     */
    public void verifyCaptcha(String code) throws Exception {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("code", code);
        p.put("timestamp", String.valueOf(System.currentTimeMillis()));
        p.put("signToken", DmCrypto.signToken(p));

        String body = "d=" + URLEncoder.encode(DmCrypto.makeD(p), "UTF-8");
        String raw = postRaw(HOST + "/v2/captcha/verify", body);
        JSONObject root = new JSONObject(raw);
        String rcode = root.optString("code", "");
        if (!"100".equals(rcode)) {
            throw new ApiEx(rcode, errMsg(root));
        }
    }

    // ------------------------------------------------------------ 底层（新增字节返回版）

    public Object post(String path, Map<String, String> extra,
                       boolean auth, boolean ts) throws Exception {
        Map<String, String> params = new LinkedHashMap<String, String>();
        if (auth) {
            params.put("uid", uid);
            params.put("token", token);
            params.put("version", version);
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getValue() != null) {
                    params.put(e.getKey(), e.getValue());
                }
            }
        }
        if (ts) {
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        }
        params.put("signToken", DmCrypto.signToken(params));

        String body = "d=" + URLEncoder.encode(DmCrypto.makeD(params), "UTF-8");
        String raw = postRaw(HOST + path, body);
        JSONObject root = new JSONObject(raw);
        String code = root.optString("code", "");
        if ("100".equals(code)) {
            return root.opt("data");
        }
        throw new ApiEx(code, errMsg(root));
    }

    private static String errMsg(JSONObject root) {
        String msg = "";
        try {
            JSONObject sheet = root.optJSONObject("actionSheet");
            if (sheet != null) {
                msg = sheet.optString("content", "");
            }
            if (msg.isEmpty()) {
                msg = root.optString("msg", "");
            }
        } catch (Throwable ignored) {
        }
        return msg;
    }

    private String postRaw(String url, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("standardUA", ua);
            conn.setRequestProperty("user-agent", "okhttp/3.11.0");
            conn.setRequestProperty("content-type", "application/x-www-form-urlencoded");
            byte[] bytes = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                throw new ApiEx("HTTP" + code, "空响应");
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            is.close();
            return bo.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 获取二进制响应（验证码图片）
     */
    private byte[] postRawBytes(String url, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("standardUA", ua);
            conn.setRequestProperty("user-agent", "okhttp/3.11.0");
            conn.setRequestProperty("content-type", "application/x-www-form-urlencoded");
            byte[] bytes = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                throw new ApiEx("HTTP" + code, "空响应");
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            is.close();
            return bo.toByteArray();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}