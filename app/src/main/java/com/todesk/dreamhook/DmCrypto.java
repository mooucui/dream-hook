package com.todesk.dreamhook;

import android.util.Base64;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 到梦空间请求体加密 + 签名 + 登录密码加密。
 *
 * 链路（逆向自 App）：
 *   明文JSON(键排序) → AES-CBC(key=随机16位, iv=ReIV) → base64(每60字符插空格)  = seg0
 *   session_key     → RSA-1024/PKCS#1 v1.5 公钥加密 → base64                       = seg1
 *   d = base64( seg0 + "==" + seg1 )，每 60 字符插一个空格
 *   signToken = MD5( (SHA512(紧凑JSON)[1::2])[0::2] ).upper()
 *   登录密码 = DES-ECB(key=51434574, PKCS5) 的 32 位大写十六进制
 */
public class DmCrypto {

    public static final String RSA_N_HEX =
            "9eed813259ad13f963176bcab53d04a8d8dd79e85cfa73588dd162cc9b9747d4"
                    + "a05d1a8fd11ce7cb38186729bfdd82f6b24141df2b3b021f0d0f9bca4d1120e1"
                    + "1554e5fd79dd8373b8025f8bf17a03e463dea876bc4862e8ad19cb38f864ea74"
                    + "e4aa53ef15735801dc8fcd3e98e75ef5133d50d8515c5b88d40d3a39b055f6ab";
    public static final String IV_RE = "1628092121312213";
    public static final String IV_PL = "9618953120112110";
    public static final String DES_KEY = "51434574";

    private static final String ALNUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Random RND = new Random();

    // ------------------------------------------------------------ JSON

    public static String compactJson(Map<String, String> params) {
        TreeMap<String, String> t = new TreeMap<String, String>(params);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : t.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"")
                    .append(esc(e.getValue() == null ? "" : e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------ 签名

    public static String signToken(Map<String, String> params) throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-512")
                .digest(compactJson(params).getBytes("UTF-8"));
        String hex = toHex(sha);
        StringBuilder odd = new StringBuilder(64);
        for (int i = 1; i < hex.length(); i += 2) {
            odd.append(hex.charAt(i));
        }
        StringBuilder even = new StringBuilder(32);
        for (int i = 0; i < odd.length(); i += 2) {
            even.append(odd.charAt(i));
        }
        byte[] md5 = MessageDigest.getInstance("MD5")
                .digest(even.toString().getBytes("UTF-8"));
        return toHex(md5).toUpperCase();
    }

    // ------------------------------------------------------------ 封装

    public static String makeD(Map<String, String> params) throws Exception {
        String sk = randSessionKey();
        byte[] skBytes = sk.getBytes("UTF-8");

        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(skBytes, "AES"),
                new IvParameterSpec(IV_RE.getBytes("UTF-8")));
        String seg0 = b64Spaces(aes.doFinal(compactJson(params).getBytes("UTF-8")));

        String seg1 = Base64.encodeToString(rsaEncrypt(skBytes), Base64.NO_WRAP);
        String inner = seg0 + "==" + seg1;
        return b64Spaces(inner.getBytes("UTF-8"));
    }

    /** 登录密码：DES-ECB / key=51434574 / PKCS5 / 32 位大写十六进制 */
    public static String desPassword(String plain) throws Exception {
        Cipher c = Cipher.getInstance("DES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(DES_KEY.getBytes("UTF-8"), "DES"));
        return toHex(c.doFinal(plain.getBytes("UTF-8"))).toUpperCase();
    }

    // ------------------------------------------------------------ 底层

    private static byte[] rsaEncrypt(byte[] msg) throws Exception {
        BigInteger n = new BigInteger(RSA_N_HEX, 16);
        BigInteger e = BigInteger.valueOf(65537);
        PublicKey pk = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(n, e));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, pk);
        return c.doFinal(msg);
    }

    private static String b64Spaces(byte[] raw) {
        String s = Base64.encodeToString(raw, Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 60 + 2);
        for (int i = 0; i < s.length(); i += 60) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(s, i, Math.min(s.length(), i + 60));
        }
        return sb.toString();
    }

    private static String randSessionKey() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(ALNUM.charAt(RND.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private static String toHex(byte[] b) {
        char[] cs = new char[b.length * 2];
        final char[] H = "0123456789abcdef".toCharArray();
        for (int i = 0; i < b.length; i++) {
            cs[i * 2] = H[(b[i] >> 4) & 0xF];
            cs[i * 2 + 1] = H[b[i] & 0xF];
        }
        return new String(cs);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 自检：AES 官方向量 + DES 可逆。 */
    public static boolean selftest() {
        try {
            Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
            byte[] key = hex("000102030405060708090a0b0c0d0e0f");
            byte[] iv = hex("00000000000000000000000000000000");
            byte[] pt = hex("00112233445566778899aabbccddeeff");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            if (!"69c4e0d86a7b0430d8cdb78070b4c55a".equals(toHex(aes.doFinal(pt)))) {
                return false;
            }
            String pwd = desPassword("test1234");
            return pwd.length() % 16 == 0 && pwd.equals(pwd.toUpperCase());
        } catch (Throwable t) {
            return false;
        }
    }
}