package com.todesk.dreamhook;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 到梦空间的网络库被混淆了（okhttp3.RealCall 这个名字不存在），抓不到响应。
 * 那就绕开网络层：直接翻 App 自己的 shared_prefs / files，把落盘的 uuid、token 抠出来。
 *
 * 跑在目标 App 进程里，所以 /data/data/<包名>/ 对我们是可读的。
 */
public class TokenFinder {

    private static final Pattern XML_ENTRY = Pattern.compile(
            "<string name=\"([^\"]+)\">([^<]{1,4096})</string>");

    private static final Pattern JSON_TOKEN = Pattern.compile(
            "\"(?:token|accessToken|access_token|loginToken|userToken)\"\\s*:\\s*\"([A-Za-z0-9._\\-+/=]{16,})\"",
            Pattern.CASE_INSENSITIVE);

    private static final String[] TOKEN_KEY_HINTS = {
            "token", "accesstoken", "login_token", "authtoken", "authorization"
    };
    private static final String[] UID_KEY_HINTS = {
            "uid", "userid", "user_id", "accountid", "memberid"
    };

    public static void scan() {
        try {
            if (!Bridge.q("token").isEmpty()) {
                return;
            }
            Context ctx = Bridge.ctx();
            if (ctx == null || ctx.getFilesDir() == null) {
                Bridge.log("扫描登录态：拿不到 Context");
                return;
            }
            File dataDir = ctx.getFilesDir().getParentFile();
            if (dataDir == null) {
                return;
            }

            String token = null;
            String uid = null;
            String from = null;

            // 1) shared_prefs 里找
            File[] prefs = new File(dataDir, "shared_prefs").listFiles();
            if (prefs != null) {
                for (File f : prefs) {
                    if (!f.isFile()) {
                        continue;
                    }
                    String xml = read(f, 400000);
                    if (xml == null || !xml.toLowerCase().contains("token")) {
                        continue;
                    }
                    String t = pickToken(xml);
                    if (t != null) {
                        token = t;
                        from = "shared_prefs/" + f.getName();
                        uid = pickUid(xml);
                        break;
                    }
                }
            }

            // 2) 退一步：files 下的 json / xml
            if (token == null) {
                token = deepScan(new File(dataDir, "files"), 0);
                if (token != null) {
                    from = "files/*";
                }
            }

            if (token == null) {
                Bridge.log("× 存储里没找到 token：可能没登录过，或者存在数据库里");
                return;
            }

            Bridge.log("✓ 从 App 存储捞到登录态（" + from + "）"
                    + " token=" + mask(token)
                    + (uid != null ? " uid=" + mask(uid) : " uid=未找到"));
            Bridge.set("token", token);
            if (uid != null) {
                Bridge.set("uid", uid);
            }
        } catch (Throwable t) {
            Bridge.log("扫描登录态异常: " + t);
        }
    }

    // ------------------------------------------------------------ 解析

    private static String pickToken(String xml) {
        Matcher m = XML_ENTRY.matcher(xml);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = m.group(2).trim();
            if (val.length() < 16 || val.contains(" ") || val.contains("<")) {
                continue;
            }
            for (String h : TOKEN_KEY_HINTS) {
                if (key.contains(h)) {
                    return val;
                }
            }
        }
        return null;
    }

    private static String pickUid(String xml) {
        Matcher m = XML_ENTRY.matcher(xml);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = m.group(2).trim();
            if (!val.matches("\\d{2,12}")) {
                continue;
            }
            for (String h : UID_KEY_HINTS) {
                if (key.contains(h)) {
                    return val;
                }
            }
        }
        return null;
    }

    private static String deepScan(File dir, int depth) {
        if (dir == null || depth > 2 || !dir.isDirectory()) {
            return null;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return null;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                String r = deepScan(f, depth + 1);
                if (r != null) {
                    return r;
                }
                continue;
            }
            String name = f.getName().toLowerCase();
            if (!(name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".txt"))) {
                continue;
            }
            String txt = read(f, 300000);
            if (txt == null) {
                continue;
            }
            Matcher m = JSON_TOKEN.matcher(txt);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String read(File f, int maxBytes) {
        BufferedReader r = null;
        try {
            if (f.length() > maxBytes) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            r = new BufferedReader(new FileReader(f));
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > maxBytes) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 6) {
            return "***";
        }
        return s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }
}