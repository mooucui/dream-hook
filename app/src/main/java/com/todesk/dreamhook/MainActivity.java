package com.todesk.dreamhook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XposedBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int SHOW_LINES = 200;
    private static final int TAB_GRAB = 0;
    private static final int TAB_LOG = 1;
    private static final int TAB_SET = 2;

    private CheckBox cbEnabled, cbScan, cbNetlog;
    private EditText etPkg, etGrabIds, etAccount, etPassword,
            etStartAt, etLead, etInterval, etTimeout;
    private TextView tvLog, tvCapture;

    private View pageGrab, pageLog, pageSet;
    private Button tabGrab, tabLog, tabSet;

    private String lastLog = "";
    private String lastCap = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 崩溃日志写到 /sdcard，方便抓堆栈
    static {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.File f = new java.io.File("/sdcard/DreamHook_crash.txt");
                    java.io.FileWriter w = new java.io.FileWriter(f, true);
                    w.write("=== " + new java.util.Date() + " ===\n");
                    e.printStackTrace(new java.io.PrintWriter(w));
                    w.write("\n");
                    w.close();
                } catch (Throwable ignored) {}
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, e);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            cbEnabled = findViewById(R.id.cbEnabled);
            cbScan = findViewById(R.id.cbScan);
            cbNetlog = findViewById(R.id.cbNetlog);
            etPkg = findViewById(R.id.etPkg);
            etGrabIds = findViewById(R.id.etGrabIds);
            etAccount = findViewById(R.id.etAccount);
            etPassword = findViewById(R.id.etPassword);
            etStartAt = findViewById(R.id.etStartAt);
            etLead = findViewById(R.id.etLead);
            etInterval = findViewById(R.id.etInterval);
            etTimeout = findViewById(R.id.etTimeout);
            tvLog = findViewById(R.id.tvLog);
            tvCapture = findViewById(R.id.tvCapture);

            pageGrab = findViewById(R.id.pageGrab);
            pageLog = findViewById(R.id.pageLog);
            pageSet = findViewById(R.id.pageSet);
            tabGrab = findViewById(R.id.tabGrab);
            tabLog = findViewById(R.id.tabLog);
            tabSet = findViewById(R.id.tabSet);

            if (tabGrab == null || tabLog == null || tabSet == null ||
                pageGrab == null || pageLog == null || pageSet == null) {
                XposedBridge.log("[DreamHook] layout 缺 id，onCreate 中止");
                return;
            }

            load();
            switchTab(TAB_GRAB);

            tabGrab.setOnClickListener(v -> switchTab(TAB_GRAB));
            tabLog.setOnClickListener(v -> switchTab(TAB_LOG));
            tabSet.setOnClickListener(v -> switchTab(TAB_SET));

            Button btnSave = findViewById(R.id.btnSave);
            if (btnSave != null) {
                btnSave.setOnClickListener(v -> {
                    save();
                    Toast.makeText(MainActivity.this, "存好了", Toast.LENGTH_SHORT).show();
                });
            }

            Button btnArm = findViewById(R.id.btnArm);
            if (btnArm != null) {
                btnArm.setOnClickListener(v -> {
                    save();
                    String missing = missingBits();
                    if (!missing.isEmpty()) {
                        Toast.makeText(MainActivity.this, "还缺：" + missing, Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs().edit().putString("arm", "1").putString("lastResult", "").apply();
                    Toast.makeText(MainActivity.this, "已开抢。到梦空间可以退后台，但别被杀。", Toast.LENGTH_LONG).show();
                    switchTab(TAB_LOG);
                });
            }

            Button btnStop = findViewById(R.id.btnStop);
            if (btnStop != null) {
                btnStop.setOnClickListener(v -> {
                    prefs().edit().putString("arm", "0").apply();
                    Toast.makeText(MainActivity.this, "已发停止信号", Toast.LENGTH_SHORT).show();
                });
            }

            Button btnClear = findViewById(R.id.btnClear);
            if (btnClear != null) {
                btnClear.setOnClickListener(v -> {
                    try {
                        getContentResolver().delete(Uri.parse("content://" + PrefsProvider.AUTH + "/log"), null, null);
                    } catch (Throwable ignored) {}
                    lastLog = "";
                    tvLog.setText("（日志已清空）");
                    Toast.makeText(MainActivity.this, "日志清了", Toast.LENGTH_SHORT).show();
                });
            }

            handler.postDelayed(refresher, 500);
        } catch (Throwable t) {
            XposedBridge.log("[DreamHook] onCreate crash: " + t);
            t.printStackTrace();
        }
    }

    private void switchTab(int which) {
        pageGrab.setVisibility(which == TAB_GRAB ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(which == TAB_LOG ? View.VISIBLE : View.GONE);
        pageSet.setVisibility(which == TAB_SET ? View.VISIBLE : View.GONE);
        tabGrab.setSelected(which == TAB_GRAB);
        tabLog.setSelected(which == TAB_LOG);
        tabSet.setSelected(which == TAB_SET);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PrefsProvider.PREFS, 0);
    }

    private void load() {
        SharedPreferences sp = prefs();
        cbEnabled.setChecked(!"0".equals(sp.getString("enabled", "")));
        cbScan.setChecked("1".equals(sp.getString("scan", "")));
        cbNetlog.setChecked("1".equals(sp.getString("netlog", "")));
        etPkg.setText(sp.getString("pkg", ""));
        etGrabIds.setText(sp.getString("grabIds", ""));
        etAccount.setText(sp.getString("account", ""));
        etPassword.setText(sp.getString("password", ""));
        etStartAt.setText(sp.getString("startAt", "0"));
        etLead.setText(sp.getString("leadSec", "30"));
        etInterval.setText(sp.getString("intervalMs", "200"));
        etTimeout.setText(sp.getString("timeoutSec", "300"));
    }

    private void save() {
        prefs().edit()
                .putString("enabled", cbEnabled.isChecked() ? "1" : "0")
                .putString("scan", cbScan.isChecked() ? "1" : "0")
                .putString("netlog", cbNetlog.isChecked() ? "1" : "0")
                .putString("pkg", etPkg.getText().toString().trim())
                .putString("grabIds", etGrabIds.getText().toString().trim())
                .putString("account", etAccount.getText().toString().trim())
                .putString("password", etPassword.getText().toString())
                .putString("startAt", etStartAt.getText().toString().trim())
                .putString("leadSec", etLead.getText().toString().trim())
                .putString("intervalMs", etInterval.getText().toString().trim())
                .putString("timeoutSec", etTimeout.getText().toString().trim())
                .apply();
    }

    private String missingBits() {
        SharedPreferences sp = prefs();
        StringBuilder sb = new StringBuilder();
        boolean hasToken = !sp.getString("uid", "").isEmpty() && !sp.getString("token", "").isEmpty();
        boolean hasPwd = !etAccount.getText().toString().trim().isEmpty() && !etPassword.getText().toString().isEmpty();
        if (!hasToken && !hasPwd) sb.append("登录态或账号密码 ");
        if (etGrabIds.getText().toString().trim().isEmpty()) sb.append("活动ID ");
        return sb.toString().trim();
    }

    private String captureStatus() {
        SharedPreferences sp = prefs();
        String uid = sp.getString("uid", "");
        String token = sp.getString("token", "");
        String ua = sp.getString("ua", "");
        String jp = sp.getString("jpush", "");
        String last = sp.getString("lastResult", "");
        String arm = sp.getString("arm", "");
        boolean hasPwd = !sp.getString("account", "").isEmpty() && !sp.getString("password", "").isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("登录态  uid: ").append(uid.isEmpty() ? "未抓到" : mask(uid));
        sb.append("   token: ").append(token.isEmpty() ? "未抓到" : mask(token));
        sb.append("\n指纹    standardUA: ").append(ua.isEmpty() ? "未就绪" : ("已就绪 " + ua.length() + " 字节"));
        sb.append("\n        jPushId: ").append(jp.isEmpty() ? "未就绪" : mask(jp));
        sb.append("\n兜底    账号密码: ").append(hasPwd ? "已填（可自动登录）" : "未填");
        sb.append("\n状态    ").append("1".equals(arm) ? "抢票中" : "待命");
        if (!last.isEmpty()) sb.append("   上次: ").append(last);
        return sb.toString();
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 6) return "***";
        return s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }

    private String tail() {
        File f = new File(getFilesDir(), "logs.txt");
        if (!f.exists()) return "（还没日志：模块启用了没？作用域勾了没？到梦空间重启了没？）";
        List<String> lines = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Throwable ignored) {}
        finally { if (r != null) { try { r.close(); } catch (Throwable ignored) {} } }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - SHOW_LINES);
        for (int i = lines.size() - 1; i >= from; i--) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }
}