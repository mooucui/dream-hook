package com.todesk.dreamhook;

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;

/**
 * 撞到验证码时把人叫过来：每 10 秒响一次 + 震，最多 30 次（5 分钟）。
 * 用户点"停止"（arm=0）或者抢票线程结束后自动闭麦。
 *
 * 跑在目标 App 的进程里，用的是它的权限；全程 try/catch，叫不醒也不能把人 App 搞崩。
 */
public class Alert {

    private static final long GAP_MS = 10000L;
    private static final int MAX_TIMES = 30;

    private static Handler handler;
    private static Runnable ticker;
    private static int times = 0;

    /** 开始连环叫，重复调用不会叠加。 */
    public static void fire() {
        if (ticker != null) {
            return;
        }
        handler = new Handler(Looper.getMainLooper());
        times = 0;
        ticker = new Runnable() {
            @Override
            public void run() {
                if (times >= MAX_TIMES) {
                    stop();
                    return;
                }
                if (!"1".equals(Bridge.q("arm"))) {
                    stop();
                    return;
                }
                times++;
                beep();
                Bridge.log("🔔 第 " + times + " 次提醒：要验证码，快去滑！");
                handler.postDelayed(this, GAP_MS);
            }
        };
        handler.post(ticker);
    }

    public static void stop() {
        if (handler != null && ticker != null) {
            handler.removeCallbacks(ticker);
        }
        ticker = null;
        times = 0;
    }

    private static void beep() {
        try {
            Context c = Bridge.ctx();
            if (c == null) {
                return;
            }
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(new long[]{0, 500, 200, 500, 200, 900}, -1);
            }
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            Ringtone r = RingtoneManager.getRingtone(c, uri);
            if (r != null) {
                r.play();
            }
        } catch (Throwable ignored) {
        }
    }
}