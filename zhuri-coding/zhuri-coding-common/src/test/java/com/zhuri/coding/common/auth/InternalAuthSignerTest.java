package com.zhuri.coding.common.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * InternalAuthSigner 单元测试（网关-下游内部身份 HMAC 签名）
 */
@DisplayName("内部身份 HMAC 签名工具测试")
class InternalAuthSignerTest {

    private static final String SECRET = "unit-test-secret";

    @Test
    @DisplayName("相同密钥与字段值 → 签名可验签通过")
    void verifyWithSameSecret() {
        String sign = InternalAuthSigner.sign(SECRET, "1001", "张三", "https://a.png");
        assertTrue(InternalAuthSigner.verify(SECRET, sign, "1001", "张三", "https://a.png"));
    }

    @Test
    @DisplayName("字段被篡改 → 验签失败")
    void verifyWithTamperedValue() {
        String sign = InternalAuthSigner.sign(SECRET, "1001", "张三", "https://a.png");
        assertFalse(InternalAuthSigner.verify(SECRET, sign, "1002", "张三", "https://a.png"));
        assertFalse(InternalAuthSigner.verify(SECRET, sign, "1001", "李四", "https://a.png"));
    }

    @Test
    @DisplayName("密钥不一致 → 验签失败")
    void verifyWithWrongSecret() {
        String sign = InternalAuthSigner.sign(SECRET, "1001", "张三", "");
        assertFalse(InternalAuthSigner.verify("other-secret", sign, "1001", "张三", ""));
    }

    @Test
    @DisplayName("缺失签名/密钥为空 → 验签失败")
    void verifyWithMissingInput() {
        assertFalse(InternalAuthSigner.verify(SECRET, null, "1001"));
        assertFalse(InternalAuthSigner.verify(null, "anything", "1001"));
        assertFalse(InternalAuthSigner.verify("", "anything", "1001"));
    }

    @Test
    @DisplayName("null 字段按空串参与签名，顺序敏感")
    void nullPartsConsistentAndOrderSensitive() {
        String signA = InternalAuthSigner.sign(SECRET, "1", null, "");
        String signB = InternalAuthSigner.sign(SECRET, "1", "", "");
        assertTrue(InternalAuthSigner.verify(SECRET, signA, "1", "", ""));
        assertTrue(InternalAuthSigner.verify(SECRET, signB, "1", "", ""));
        // 顺序不同 → 签名不同
        assertNotEquals(InternalAuthSigner.sign(SECRET, "1", "2", ""),
            InternalAuthSigner.sign(SECRET, "2", "1", ""));
    }
}
