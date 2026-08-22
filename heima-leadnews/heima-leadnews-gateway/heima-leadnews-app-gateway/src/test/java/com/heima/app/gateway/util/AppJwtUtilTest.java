package com.heima.app.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AppJwtUtil（网关）单元测试
 *
 * 覆盖 token 校验（null / 未过期 / 已过期）、密钥配置与初始化校验，
 * 以及使用真实 HS512 签名的 token 生成-解析往返验证 getClaimsBody。
 */
@DisplayName("AppJwtUtil 网关 JWT 工具")
class AppJwtUtilTest {

    // 64 字节（512 bit）密钥的 base64，满足 HS512 最小长度要求
    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".getBytes());

    @AfterEach
    void tearDown() {
        // 复位静态 secret，避免影响其他测试
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey("");
    }

    @Test
    @DisplayName("verifyToken(null) → false")
    void testVerifyNull() {
        assertFalse(AppJwtUtil.verifyToken(null));
    }

    @Test
    @DisplayName("verifyToken 未过期 → true")
    void testVerifyValid() {
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey(SECRET);
        Claims claims = new DefaultClaims();
        claims.setExpiration(new Date(System.currentTimeMillis() + 600_000));

        assertTrue(AppJwtUtil.verifyToken(claims));
    }

    @Test
    @DisplayName("verifyToken 已过期 → false")
    void testVerifyExpired() {
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey(SECRET);
        Claims claims = new DefaultClaims();
        claims.setExpiration(new Date(System.currentTimeMillis() - 600_000));

        assertFalse(AppJwtUtil.verifyToken(claims));
    }

    @Test
    @DisplayName("配置密钥后 generalKey 可生成非空密钥")
    void testGeneralKey() {
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey(SECRET);

        SecretKey key = AppJwtUtil.generalKey();
        assertNotNull(key);
        assertTrue(key.getEncoded().length > 0);
    }

    @Test
    @DisplayName("token 生成-解析往返 → 能取回 claims")
    void testGetClaimsBodyRoundTrip() {
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey(SECRET);

        SecretKey key = AppJwtUtil.generalKey();
        String token = Jwts.builder()
                .claim("userId", 100L)
                .setExpiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();

        Claims claims = AppJwtUtil.getClaimsBody(token);
        assertNotNull(claims);
        assertTrue(AppJwtUtil.verifyToken(claims));
    }

    @Test
    @DisplayName("未配置 secret 时 init 抛异常")
    void testInitNoSecret() {
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey(null);
        assertThrows(IllegalStateException.class, util::init);
    }

    @Test
    @DisplayName("配置 secret 后 init 不抛异常")
    void testInitWithSecret() {
        AppJwtUtil util = new AppJwtUtil();
        util.setTokenEncryKey(SECRET);
        util.init();
    }
}