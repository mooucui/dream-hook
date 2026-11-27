package com.todesk.dreamhook;

import android.app.Application;
import android.app.Instrumentation;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import dalvik.system.DexFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口。抓登录态三条路，总有一条能走通：
 *   A. OkHttp 钩子（能被混淆干掉，到梦空间就是这种情况）
 *   B. SharedPreferences 写盘钩子（系统框架类，混淆动不了）← 主力
 *   C. 翻 App 存储里的 xml/json 文件（静态兜底）
 * 再加一条：D. 真的抓不到，就用界面填的账号密码自己登录。
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String SELF = "com.todesk.dreamhook";

    private static boolean armWatcherStarted = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (SELF.equals(lpparam.packageName)) {
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                init(lpparam);
                            } catch (Throwable t) {
                                XposedBridge.log("[DreamHook] init 失败: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[DreamHook] hook Application 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 初始化

    private void init(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Bridge.on("enabled")) {
            return;
        }
        String target = Bridge.q("pkg").trim();
        if (!target.isEmpty() && !lpparam.packageName.contains(target)) {
            return;
        }

        Bridge.log("==== 进程启动: " + lpparam.processName + " ====");

        hookOkHttp(lpparam.classLoader);
        SpHook.install(lpparam.classLoader);
        TokenFinder.scan();
        DeviceProfile.ensure();

        boolean main = lpparam.processName == null
                || lpparam.processName.equals(lpparam.packageName);
        if (main) {
            startArmWatcher();
        }
        if (Bridge.on("scan")) {
            final ClassLoader cl = lpparam.classLoader;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        scanApk(cl);
                    } catch (Throwable e) {
                        Bridge.log("类扫描崩了: " + e);
                    }
                }
            });
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    /** 用户点了"开抢"就起线程。 */
    private void startArmWatcher() {
        if (armWatcherStarted) {
            return;
        }
        armWatcherStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if ("1".equals(Bridge.q("arm")) && !Grabber.running) {
                            Grabber.start();
                        }
                        Thread.sleep(800);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "dm-arm");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ OKHttp（能钩就钩）

    private void hookOkHttp(final ClassLoader cl) {
        try {
            Class<?> realCall = XposedHelpers.findClass("okhttp3.RealCall", cl);

            final XC_MethodHook reqHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    onRequest(param.thisObject);
                }
            };
            XposedHelpers.findAndHookMethod(realCall, "execute", reqHook);
            XposedHelpers.findAndHookMethod(realCall, "enqueue", "okhttp3.Callback", reqHook);

            final XC_MethodHook respHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onResponse(param.getResult());
                }
            };
            XposedHelpers.findAndHookMethod(realCall, "execute", respHook);

            String[] names = {"getResponseWithInterceptorChain",
                    "getResponseWithInterceptorChain$okhttp"};
            for (String n : names) {
                try {
                    XposedHelpers.findAndHookMethod(realCall, n, respHook);
                    Bridge.log("响应钩子: " + n);
                } catch (Throwable ignored) {
                }
            }
            hookCallback(cl);
            Bridge.log("OkHttp 钩子挂完");
        } catch (Throwable t) {
            Bridge.log("挂 OkHttp 失败（网络库被混淆，走 SharedPreferences 兜底）");
        }
    }

    private void hookCallback(ClassLoader cl) {
        try {
            Class<?> cb = XposedHelpers.findClass("okhttp3.Callback", cl);
            XposedHelpers.findAndHookMethod(cb, "onResponse",
                    "okhttp3.Call", "okhttp3.Response", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object[] a = param.args;
                                onResponse(a != null && a.length > 1 ? a[1] : null);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Bridge.log("Callback 钩子: ok");
        } catch (Throwable ignored) {
        }
    }

    private void onRequest(Object call) {
        try {
            Object req = XposedHelpers.getObjectField(call, "originalRequest");
            if (req == null) {
                return;
            }
            Object headers = XposedHelpers.callMethod(req, "headers");
            String ua = (String) XposedHelpers.callMethod(headers, "get", "standardUA");
            if (ua == null || ua.isEmpty()) {
                return;
            }
            if (!ua.equals(Bridge.q("ua"))) {
                Bridge.set("ua", ua);
                try {
                    JSONObject j = new JSONObject(ua);
                    String jp = j.optString("jPushId", "");
                    String ver = j.optString("version", "");
                    if (!jp.isEmpty()) {
                        Bridge.set("jpush", jp);
                    }
                    if (!ver.isEmpty()) {
                        Bridge.set("appVersion", ver);
                    }
                } catch (Throwable ignored) {
                }
                Bridge.log("✓ 抓到真身设备指纹");
            }
        } catch (Throwable ignored) {
        }
    }

    private void onResponse(Object resp) {
        if (resp == null) {
            return;
        }
        try {
            Object req = XposedHelpers.callMethod(resp, "request");
            String url = String.valueOf(XposedHelpers.callMethod(req, "url"));
            if (!(url.contains("login") || url.contains("/v3/user"))) {
                return;
            }
            Object body = XposedHelpers.callMethod(resp, "peekBody", 65536L);
            if (body == null) {
                return;
            }
            String s = (String) XposedHelpers.callMethod(body, "string");
            if (s == null || !s.contains("\"token\"")) {
                return;
            }
            JSONObject root = new JSONObject(s);
            JSONObject data = root.optJSONObject("data");
            String uid = data != null ? data.optString("uid", "") : root.optString("uid", "");
            String token = data != null ? data.optString("token", "") : root.optString("token", "");
            if (uid.isEmpty() || token.isEmpty()) {
                return;
            }
            if (!token.equals(Bridge.q("token"))) {
                Bridge.set("uid", uid);
                Bridge.set("token", token);
                Bridge.log("✓ 抓到登录态 token=" + mask(token));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 6) {
            return "***";
        }
        return s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }

    // ------------------------------------------------------------------ 类扫描

    private void scanApk(ClassLoader cl) {
        android.content.Context ctx = Bridge.ctx();
        if (ctx == null) {
            Bridge.log("类扫描跳过：拿不到 Application");
            return;
        }
        String src = ctx.getApplicationInfo().sourceDir;
        String self = ctx.getPackageName();
        Bridge.log("开始扫 dex: " + src);

        DexFile dex;
        try {
            dex = new DexFile(new File(src));
        } catch (Throwable t) {
            Bridge.log("DexFile 打不开（大概率加了壳）: " + t);
            return;
        }

        List<String> hits = new ArrayList<String>();
        int total = 0;
        try {
            Enumeration<String> en = dex.entries();
            String selfLow = self.toLowerCase();
            while (en.hasMoreElements() && total < 80000) {
                String cn = en.nextElement();
                total++;
                if (cn == null) {
                    continue;
                }
                String low = cn.toLowerCase();
                boolean own = low.startsWith(selfLow);
                boolean key = low.contains("signup") || low.contains("enroll")
                        || low.contains("baoming") || low.contains("apply")
                        || low.contains("join") || low.contains("activity");
                if (own || key) {
                    hits.add(cn);
                }
            }
        } catch (Throwable t) {
            Bridge.log("枚举类失败: " + t);
        }

        Bridge.log("dex 类总数: " + total + "，命中: " + hits.size());
        int logged = 0;
        for (String cn : hits) {
            if (logged >= 1200) {
                Bridge.log("（还有 " + (hits.size() - logged) + " 个没打）");
                break;
            }
            StringBuilder sb = new StringBuilder("类 ").append(cn);
            try {
                Class<?> c = Class.forName(cn, false, cl);
                Method[] ms = c.getDeclaredMethods();
                int k = 0;
                for (Method m : ms) {
                    if (k >= 10) {
                        sb.append("\n    ...");
                        break;
                    }
                    sb.append("\n    - ").append(m.getName())
                            .append("(").append(m.getParameterTypes().length).append("参)");
                    k++;
                }
            } catch (Throwable ignored) {
            }
            Bridge.log(sb.toString());
            logged++;
        }
        Bridge.log("==== 类扫描结束 ====");
    }
}