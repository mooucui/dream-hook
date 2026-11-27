package com.todesk.dreamhook;

import java.util.Random;

/**
 * 设备指纹兜底：偷不到 App 真身发的 standardUA，就自己造一套。
 * 只要「UA 里的 jPushId」和「submit 参数里的 jPushId」一致，服务端就认。
 */
public class DeviceProfile {

    private static final Random RND = new Random();
    private static final String[] DEVICES = {"V2301A", "PGT-AN10", "PJD110", "ALN-AL00"};
    private static final String[] HARDWARE = {"qcom/sm8475", "qcom/sm8550", "qcom/sm8650"};
    private static final String[] SCREEN = {"1260-2800", "1176-2500", "1264-2780", "1216-2688"};

    public static void ensure() {
        try {
            if (!Bridge.q("ua").trim().isEmpty()) {
                return;
            }
            String uuid = hex(32);
            String jpush = hex(13);
            long now = System.currentTimeMillis();
            int i = RND.nextInt(DEVICES.length);

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"channelName\":\"yingyongbao\"");
            sb.append(",\"countryCode\":\"CN\"");
            sb.append(",\"createTime\":").append(now - 86400000L);
            sb.append(",\"device\":\"").append(DEVICES[i]).append("\"");
            sb.append(",\"hardware\":\"").append(HARDWARE[i % HARDWARE.length]).append("\"");
            sb.append(",\"jPushId\":\"").append(jpush).append("\"");
            sb.append(",\"modifyTime\":").append(now - 3600000L);
            sb.append(",\"operator\":\"wifi\"");
            sb.append(",\"screenResolution\":\"").append(SCREEN[i % SCREEN.length]).append("\"");
            sb.append(",\"startTime\":").append(now - 600000L);
            sb.append(",\"sysVersion\":\"Android 15 35\"");
            sb.append(",\"system\":\"android\"");
            sb.append(",\"uuid\":\"").append(uuid).append("\"");
            sb.append(",\"version\":\"5.0.0\"");
            sb.append("}");

            Bridge.set("ua", sb.toString());
            Bridge.set("jpush", jpush);
            Bridge.set("appVersion", "5.0.0");
            Bridge.log("设备指纹：偷不到真身的，已自造一套（jPushId=" + jpush + "）");
        } catch (Throwable t) {
            Bridge.log("造设备指纹失败: " + t);
        }
    }

    private static String hex(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append("0123456789abcdef".charAt(RND.nextInt(16)));
        }
        return sb.toString();
    }
}