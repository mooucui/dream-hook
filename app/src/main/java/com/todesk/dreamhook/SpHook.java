package com.todesk.dreamhook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 兜底一条路：钩 SharedPreferences 的写盘动作。
 *
 * 为什么这条能成：
 *   1. 到梦空间的网络库被混淆了，okhttp3.RealCall 这个名字根本不存在，抓响应没戏；
 *   2. 但它保存登录态一定走 android.app.SharedPreferencesImpl$EditorImpl.putString，
 *      这是系统框架类，**混淆动不了它**；
 *   3. 只要它写 token / uid，我们当场截下来。
 */
public class SpHook {

    private static final String[] TOKEN_HINTS = {
            "token", "accesstoken", "authtoken", "login_token", "user_token"
    };
    private static final String[] UID_HINTS = {
            "uid", "userid", "user_id", "accountid", "memberid"
    };

    public static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl$EditorImpl", cl,
                    "putString", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                check((String) p.args[0], (String) p.args[1]);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl$EditorImpl", cl,
                    "putInt", String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                int v = (Integer) p.args[1];
                                if (v > 0) {
                                    check((String) p.args[0], String.valueOf(v));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Bridge.log("SharedPreferences 钩子已挂（落盘时截 token）");
        } catch (Throwable t) {
            Bridge.log("挂 SharedPreferences 失败: " + t);
        }
    }

    private static void check(String key, String val) {
        if (key == null || val == null || val.isEmpty() || val.length() > 4096) {
            return;
        }
        String k = key.toLowerCase();

        for (String h : TOKEN_HINTS) {
            if (k.contains(h)) {
                if (val.length() >= 16 && val.indexOf(' ') < 0
                        && !val.equals(Bridge.q("token"))) {
                    Bridge.set("token", val);
                    Bridge.log("✓ 落盘截到 token=" + mask(val) + "（key=" + key + "）");
                }
                return;
            }
        }
        for (String h : UID_HINTS) {
            if (k.contains(h)) {
                if (val.matches("\\d{2,12}") && !val.equals(Bridge.q("uid"))) {
                    Bridge.set("uid", val);
                    Bridge.log("✓ 落盘截到 uid=" + mask(val) + "（key=" + key + "）");
                }
                return;
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