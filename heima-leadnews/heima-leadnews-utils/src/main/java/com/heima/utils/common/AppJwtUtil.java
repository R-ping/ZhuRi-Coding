package com.heima.utils.common;

import io.jsonwebtoken.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

public class AppJwtUtil {

    // TOKEN的有效期一小时（S）
    private static final int TOKEN_TIME_OUT = 3_600;
    // 最小刷新间隔(S)
    private static final int REFRESH_TIME = 600;
    /**
     * 获取JWT签名密钥，从环境变量 JWT_SECRET 读取（与网关模块统一）
     * 生产环境必须设置该环境变量，无默认值
     */
    private static String getTokenEncryKey() {
        String envKey = System.getenv("JWT_SECRET");
        if (envKey == null || envKey.isBlank()) {
            throw new IllegalStateException("JWT secret is not configured. Set 'JWT_SECRET' environment variable");
        }
        return envKey;
    }

    // 生产ID
    public static String getToken(Long id) {
        Map<String, Object> claimMaps = new HashMap<>();
        claimMaps.put("userId", id);
        long currentTime = System.currentTimeMillis();
        return Jwts.builder()
            .setId(UUID.randomUUID().toString())
            .setIssuedAt(new Date(currentTime))  //签发时间
            .setSubject("system")  //说明
            .setIssuer("heima") //签发者信息
            .setAudience("app")  //接收用户
            .compressWith(CompressionCodecs.GZIP)  //数据压缩方式
            .signWith(generalKey(), SignatureAlgorithm.HS512) //加密方式
            .setExpiration(new Date(currentTime + TOKEN_TIME_OUT * 1000))  //过期时间戳
            .addClaims(claimMaps) //cla信息
            .compact();
    }

    /**
     * 生成JWT access token（带更多claims，用于双token体系）
     * @param id       用户ID
     * @param extraClaims 额外claims（如 name, phone 等）
     * @return JWT token
     */
    public static String getToken(Long id, Map<String, Object> extraClaims) {
        Map<String, Object> claimMaps = new HashMap<>();
        claimMaps.put("userId", id);
        if (extraClaims != null) {
            claimMaps.putAll(extraClaims);
        }
        long currentTime = System.currentTimeMillis();
        return Jwts.builder()
            .setId(UUID.randomUUID().toString())
            .setIssuedAt(new Date(currentTime))
            .setSubject("system")
            .setIssuer("heima")
            .setAudience("app")
            .compressWith(CompressionCodecs.GZIP)
            .signWith(generalKey(), SignatureAlgorithm.HS512)
            .setExpiration(new Date(currentTime + TOKEN_TIME_OUT * 1000))  // 1小时过期
            .addClaims(claimMaps)
            .compact();
    }

    /**
     * 获取token中的claims信息
     */
    private static Jws<Claims> getJws(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(generalKey())
            .build()
            .parseClaimsJws(token);
    }

    /**
     * 获取payload body信息
     */
    public static Claims getClaimsBody(String token) {
        try {
            return getJws(token).getBody();
        } catch (ExpiredJwtException e) {
            return null;
        }
    }

    /**
     * 获取header body信息
     */
    public static JwsHeader getHeaderBody(String token) {
        return getJws(token).getHeader();
    }

    /**
     * 是否过期
     *
     * @return -1：有效，0：有效，1：过期，2：过期
     */
    public static int verifyToken(Claims claims) {
        if (claims == null) {
            return 1;
        }
        try {
            claims.getExpiration()
                .before(new Date());
            // 需要自动刷新TOKEN
            if ((claims.getExpiration().getTime() - System.currentTimeMillis()) > REFRESH_TIME * 1000) {
                return -1;
            } else {
                return 0;
            }
        } catch (ExpiredJwtException ex) {
            return 1;
        } catch (Exception e) {
            return 2;
        }
    }

    /**
     * 由字符串生成加密key
     */
    public static SecretKey generalKey() {
        byte[] encodedKey = Base64.getDecoder().decode(getTokenEncryKey());
        return new SecretKeySpec(encodedKey, SignatureAlgorithm.HS512.getJcaName()); // HmacSHA512
    }

    public static void main(String[] args) {

    }

}
