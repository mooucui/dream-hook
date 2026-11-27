package com.todesk.dreamhook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 被注入进程 <-> 模块 App 的通道。
 *   读配置：content://com.todesk.dreamhook.prefs/<key>
 *   写配置：content://com.todesk.dreamhook.prefs/kv   （kv 里放 k / v）
 *   写日志：content://com.todesk.dreamhook.prefs/log  （line）
 *
 * 缓存 1.2 秒，因为抢票循环里问 "arm" 不能每次都跨进程。
 */
public class Bridge {

    public static final String AUTH = "com.todesk.dreamhook.prefs";
    private static final long TTL_MS = 1200L;

    private static final Map<String, String> CACHE = new HashMap<String, String>();
    private static final Map<String, Long> CACHED_AT = new HashMap<String, Long>();
    private static Context sApp = null;

    public static Context ctx() {
        if (sApp != null) {
            return sApp;
        }
        try {
            Class<?> c = Class.forName("android.app.AndroidAppHelper");
            Object o = c.getMethod("currentApplication").invoke(null);
            if (o instanceof Context) {
                sApp = (Context) o;
                return sApp;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object o = at.getMethod("currentApplication").invoke(null);
            if (o instanceof Context) {
                sApp = (Context) o;
                return sApp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void log(String s) {
        try {
            Context c = ctx();
            if (c == null) {
                XposedBridge.log("[DreamHook] " + s);
                return;
            }
            ContentValues v = new ContentValues();
            v.put("line", s);
            c.getContentResolver().insert(Uri.parse("content://" + AUTH + "/log"), v);
        } catch (Throwable t) {
            XposedBridge.log("[DreamHook] " + s);
        }
    }

    public static void set(String key, String value) {
        CACHE.put(key, value);
        CACHED_AT.put(key, System.currentTimeMillis());
        try {
            Context c = ctx();
            if (c == null) {
                return;
            }
            ContentValues v = new ContentValues();
            v.put("k", key);
            v.put("v", value);
            c.getContentResolver().insert(Uri.parse("content://" + AUTH + "/kv"), v);
        } catch (Throwable ignored) {
        }
    }

    public static String q(String key) {
        Long at = CACHED_AT.get(key);
        if (at != null && System.currentTimeMillis() - at < TTL_MS) {
            String hit = CACHE.get(key);
            return hit == null ? "" : hit;
        }
        String v = "";
        try {
            Context c = ctx();
            if (c != null) {
                Cursor cur = c.getContentResolver()
                        .query(Uri.parse("content://" + AUTH + "/" + key), null, null, null, null);
                if (cur != null) {
                    if (cur.moveToFirst() && !cur.isNull(0)) {
                        v = cur.getString(0);
                    }
                    cur.close();
                }
            }
        } catch (Throwable ignored) {
        }
        CACHE.put(key, v);
        CACHED_AT.put(key, System.currentTimeMillis());
        return v;
    }

    /** 空字符串视为开，"0" 视为关 */
    public static boolean on(String key) {
        return !"0".equals(q(key));
    }
}