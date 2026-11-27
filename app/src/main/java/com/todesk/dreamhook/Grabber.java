package com.todesk.dreamhook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 抢票线程：跑在到梦空间进程里，毫秒级轮询。
 *
 * 登录态优先级：抓到的 / 存储里捞的 → 没有就用界面填的账号密码自己登。
 *
 * 逻辑：
 *   提前 lead 秒进窗口 → 每 interval 毫秒提交一次
 *   「不在报名时间 / 未开始」→ 继续重试
 *   「已报名 / 名额已满 / 已结束」→ 立刻停
 *   「需要验证」→ 连环响铃把人叫来，自动降频到 5 秒一次等你滑
 *   「token 失效」→ 自动重登一次
 */
public class Grabber {

    private static final long SLOW_INTERVAL = 5000L;

    public static volatile boolean running = false;
    private static Thread thread;

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "dm-grab");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public static void stop() {
        running = false;
    }

    private static boolean armed() {
        return "1".equals(Bridge.q("arm"));
    }

    private static long num(String key, long def) {
        try {
            String s = Bridge.q(key).trim();
            if (s.isEmpty()) {
                return def;
            }
            return Long.parseLong(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean isCaptcha(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("验证") || msg.contains("滑动") || msg.contains("人机")
                || msg.contains("安全校验") || msg.toLowerCase().contains("captcha");
    }

    private static boolean isTokenDead(String code) {
        return "10004".equals(code) || "10005".equals(code) || "10007".equals(code);
    }

    /** 用界面上填的账号密码重新登录，成功返回新 client。 */
    private static DmClient relogin(String why) {
        String acc = Bridge.q("account").trim();
        String pwd = Bridge.q("password");
        if (acc.isEmpty() || pwd.isEmpty()) {
            Bridge.log("× " + why + "，但没填账号密码，没法自动重登");
            return null;
        }
        Bridge.log("登录态失效（" + why + "），用账号自动重登……");
        try {
            DmClient c = new DmClient();
            JSONObject d = c.login(acc, pwd);
            Bridge.log("✓ 重登成功：" + d.optString("displayName", "")
                    + " / " + d.optString("schoolName", ""));
            return new DmClient();
        } catch (Throwable t) {
            Bridge.log("× 重登失败: " + t);
            return null;
        }
    }

    private static void loop() {
        try {
            String ids = Bridge.q("grabIds").trim();
            if (ids.isEmpty()) {
                Bridge.log("没填活动ID，退出");
                return;
            }
            DmClient c = new DmClient();
            if (!c.hasSession()) {
                c = relogin("没有可用登录态");
                if (c == null) {
                    Bridge.log("× 没有登录态：要么在到梦空间里登录一次，"
                            + "要么在模块里填账号密码");
                    return;
                }
            }
            if (!c.ready()) {
                Bridge.log("× 还缺：" + c.missing());
                return;
            }

            long lead = num("leadSec", 15) * 1000L;
            long interval = Math.max(80, num("intervalMs", 200));
            long timeoutSec = num("timeoutSec", 300);
            long startAt = num("startAt", 0);
            Bridge.log("抢票开始 · 间隔 " + interval + "ms · 提前 " + (lead / 1000) + "s");

            String[] arr = ids.split("[,，\\s]+");
            for (String id : arr) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (!running || !armed()) {
                    break;
                }
                grabOne(c, id, startAt, lead, interval, timeoutSec);
            }
            Bridge.log("抢票线程结束");
        } catch (Throwable t) {
            Bridge.log("抢票线程异常: " + t);
        } finally {
            running = false;
            Bridge.set("arm", "0");
            Alert.stop();
        }
    }

    private static void grabOne(DmClient c, String id, long startAt,
                                long lead, long interval, long timeoutSec) {
        long start = startAt;
        String title = "";
        if (start <= 0) {
            try {
                Object data = c.detail(id);
                Long v = findLong(data, "joinstart", 0);
                if (v != null && v > 0) {
                    start = v;
                }
                String t = findStr(data, "title");
                if (t != null) {
                    title = t;
                }
            } catch (DmClient.ApiEx e) {
                if (isTokenDead(e.code)) {
                    DmClient n = relogin("拉详情 " + e.code);
                    if (n != null) {
                        c = n;
                    }
                }
                Bridge.log("拉详情失败: " + e.getMessage());
            } catch (Throwable t) {
                Bridge.log("拉详情失败: " + t);
            }
        }
        Bridge.log("=== 活动 " + id + (title.isEmpty() ? "" : " · " + title) + " ===");

        if (start > 0) {
            Bridge.log("报名开始: " + fmt(start));
            long fire = start - lead;
            while (running && armed()) {
                long left = fire - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(500, Math.max(20, left)));
                } catch (InterruptedException e) {
                    return;
                }
            }
            Bridge.log("进窗口，开抢");
        } else {
            Bridge.log("没拿到报名开始时间，直接开打");
        }

        long deadline = (start > 0 ? start : System.currentTimeMillis()) + timeoutSec * 1000L;
        int n = 0;
        boolean captchaNotified = false;
        long wait = interval;

        while (running && armed() && System.currentTimeMillis() < deadline) {
            n++;
            long t0 = System.currentTimeMillis();
            try {
                c.submit(id);
                long cost = System.currentTimeMillis() - t0;
                Bridge.log("√√√ 第" + n + "次提交成功！活动 " + id + "（" + cost + "ms）");
                Bridge.set("lastResult", "成功 " + id);
                Alert.stop();
                return;
            } catch (DmClient.ApiEx e) {
                long cost = System.currentTimeMillis() - t0;
                String m = e.getMessage();
                Bridge.log("第" + n + "次 " + cost + "ms " + m);

                if (isTokenDead(e.code)) {
                    DmClient nc = relogin("提交 " + e.code);
                    if (nc == null) {
                        Bridge.set("lastResult", "登录失效");
                        return;
                    }
                    c = nc;
                    continue;
                }

                if (m.contains("已报名") || m.contains("已经报过") || m.contains("名额已满")
                        || m.contains("已结束") || "2000011".equals(e.code)) {
                    Bridge.log("× 停手: " + m);
                    Bridge.set("lastResult", "结束 " + m);
                    Alert.stop();
                    return;
                }

                if (isCaptcha(m)) {
                    if (!captchaNotified) {
                        captchaNotified = true;
                        Bridge.log("⚠⚠ 这场要验证码：已开始连环响铃，请马上打开到梦空间手动滑一下");
                        Bridge.log("⚠⚠ 滑完它会自己报名成功；我降频到 5 秒一次等你");
                        Alert.fire();
                    }
                    wait = SLOW_INTERVAL;
                }
            } catch (Throwable t) {
                Bridge.log("第" + n + "次异常: " + t);
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
        Bridge.log("× 超时或被停止，放弃活动 " + id);
        Bridge.set("lastResult", "放弃 " + id);
    }

    // ------------------------------------------------------------ JSON 工具

    private static Long findLong(Object o, String keyPart, int depth) {
        if (o == null || depth > 5) {
            return null;
        }
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.opt(k);
                if (k.toLowerCase().contains(keyPart)) {
                    Long l = asEpoch(v);
                    if (l != null) {
                        return l;
                    }
                }
                Long r = findLong(v, keyPart, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length() && i < 30; i++) {
                Long r = findLong(a.opt(i), keyPart, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static String findStr(Object o, String keyPart) {
        if (o == null) {
            return null;
        }
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(keyPart)) {
                return j.optString(keyPart, null);
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equalsIgnoreCase(keyPart)) {
                    return j.optString(k, null);
                }
            }
        }
        return null;
    }

    /** 支持 epoch 秒 / 毫秒 / "2026.11.27 11:11" / "2026-11-27 11:11:00"。 */
    private static Long asEpoch(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            long n = Long.parseLong(s);
            if (n > 100000000000L) {
                return n;
            }
            if (n > 1000000000L) {
                return n * 1000L;
            }
            return null;
        } catch (Throwable ignored) {
        }
        String s2 = s.replace('.', '-').replace('/', '-').replace('T', ' ');
        if (s2.length() > 19) {
            s2 = s2.substring(0, 19);
        }
        String[] fmts = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String f : fmts) {
            try {
                Date d = new SimpleDateFormat(f, Locale.CHINA).parse(s2);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }
}