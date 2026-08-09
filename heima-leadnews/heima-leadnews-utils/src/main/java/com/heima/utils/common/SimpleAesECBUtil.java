package com.heima.utils.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class SimpleAesECBUtil {
    // 全局密钥16位（从环境变量读取，默认值仅用于开发环境）
    private static final String secretKey = System.getenv("AES_SECRET_KEY") != null ? System.getenv("AES_SECRET_KEY") : "abc1234567890xyz";
    private static final String TRANSFORM = "AES/ECB/PKCS5Padding";

    public static String encrypt(String content) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] bytes = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    public static String decrypt(String base64Str) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decode = Base64.getDecoder().decode(base64Str);
            return new String(cipher.doFinal(decode), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}