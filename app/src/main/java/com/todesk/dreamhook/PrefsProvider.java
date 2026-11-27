package com.todesk.dreamhook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 通道实现。三个入口：
 *   query  /<key>   → 读一个配置项
 *   insert /kv      → 写一个配置项（被注入进程回写抓到的 uid/token）
 *   insert /log     → 追一行日志
 *   delete          → 清日志
 */
public class PrefsProvider extends ContentProvider {

    public static final String AUTH = "com.todesk.dreamhook.prefs";
    public static final String PREFS = "cfg";
    private static final String LOG_FILE = "logs.txt";
    private static final long MAX_LOG_BYTES = 600 * 1024L;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String key = uri.getLastPathSegment();
        String value = "";
        try {
            SharedPreferences sp = getContext().getSharedPreferences(PREFS, 0);
            value = sp.getString(key, "");
        } catch (Throwable ignored) {
        }
        MatrixCursor c = new MatrixCursor(new String[]{"v"});
        c.addRow(new Object[]{value});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            String seg = uri.getLastPathSegment();
            if ("kv".equals(seg)) {
                String k = values == null ? null : values.getAsString("k");
                String v = values == null ? "" : values.getAsString("v");
                if (k != null && !k.isEmpty()) {
                    getContext().getSharedPreferences(PREFS, 0).edit()
                            .putString(k, v == null ? "" : v).apply();
                }
            } else {
                String line = values == null ? "" : values.getAsString("line");
                append(line == null ? "" : line);
            }
        } catch (Throwable ignored) {
        }
        return uri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            File f = logFile();
            if (f.exists()) {
                f.delete();
            }
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    private File logFile() {
        return new File(getContext().getFilesDir(), LOG_FILE);
    }

    private void append(String line) throws Exception {
        File f = logFile();
        if (f.exists() && f.length() > MAX_LOG_BYTES) {
            f.delete();
        }
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
        BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
        try {
            w.write(ts + "  " + line);
            w.newLine();
        } finally {
            w.close();
        }
    }
}