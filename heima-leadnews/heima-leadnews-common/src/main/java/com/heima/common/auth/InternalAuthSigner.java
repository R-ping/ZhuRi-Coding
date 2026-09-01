package com.heima.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网关与下游服务之间的内部身份签名工具（HMAC-SHA256）。
 * <p>
 * 网关把 JWT 解析出的 userId/nickName/image 明文写入请求头转发下游时，
 * 同步写入 X-Internal-Sign 头（对原始值做 HMAC）。下游拦截器验签通过后才信任这些头，
 * 防止攻击者绕过网关直连下游、伪造 userId 头冒充任意用户。
 * <p>
 * 密钥 {@code app.internal-auth.secret} 在网关与各服务间共享；未配置密钥时
 * 网关不写签名、下游跳过校验（兼容本地直连/降级），配置后强制启用。
 */
public final class InternalAuthSigner {

    /** 签名请求头名称 */
    public static final String HEADER_SIGN = "X-Internal-Sign";

    /** 防篡改固定前缀（防止对空 parts 的平凡签名碰撞） */
    private static final String PREFIX = "heima-leadnews-internal-v1";

    private InternalAuthSigner() {
    }

    /**
     * 对参与防伪的字段值计算 HMAC-SHA256 签名（十六进制小写）
     *
     * @param secret 共享密钥
     * @param parts  参与签名的原始值（与校验方传入顺序一致；null 按空串处理）
     */
    public static String sign(String secret, String... parts) {
        String payload = PREFIX + "|" + join(parts);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("内部身份签名计算失败", e);
        }
    }

    /**
     * 校验签名是否匹配（常量时间比较，防时序攻击）
     *
     * @param secret 共享密钥
     * @param sign   请求头携带的签名
     * @param parts  与签名时一致的原始值
     */
    public static boolean verify(String secret, String sign, String... parts) {
        if (secret == null || secret.isEmpty() || sign == null || sign.isEmpty()) {
            return false;
        }
        String expected = sign(secret, parts);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            sign.getBytes(StandardCharsets.UTF_8));
    }

    private static String join(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append('|').append(part == null ? "" : part);
        }
        return sb.substring(1);
    }
}
