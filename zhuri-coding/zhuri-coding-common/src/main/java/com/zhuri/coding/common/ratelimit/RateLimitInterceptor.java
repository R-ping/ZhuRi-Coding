package com.heima.common.ratelimit;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 简易 API 限流拦截器 — 基于令牌桶算法
 * 针对登录、注册、文件上传等高频/敏感接口进行限流
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 默认最大请求数（每分钟） */
    private static final int DEFAULT_MAX_REQUESTS = 60;
    /** 登录接口限流（每分钟） */
    private static final int LOGIN_MAX_REQUESTS = 10;
    /** 上传接口限流（每分钟） */
    private static final int UPLOAD_MAX_REQUESTS = 20;

    /** 令牌桶存储: key = "ip:path", value = [可用令牌数, 上次刷新时间] */
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        String clientIp = getClientIp(request);

        // 仅对登录和上传接口限流
        int maxRequests;
        if (path.contains("/login") || path.contains("/login_auth") || path.contains("/register")) {
            maxRequests = LOGIN_MAX_REQUESTS;
        } else if (path.contains("/upload") || path.contains("/material")) {
            maxRequests = UPLOAD_MAX_REQUESTS;
        } else {
            return true; // 其他接口不限流
        }

        String key = clientIp + ":" + path;
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(maxRequests));

        synchronized (bucket) {
            long now = System.currentTimeMillis();
            // 每分钟重置令牌
            if (now - bucket.lastRefillTime > TimeUnit.MINUTES.toMillis(1)) {
                bucket.tokens = maxRequests;
                bucket.lastRefillTime = now;
            }
            if (bucket.tokens <= 0) {
                log.warn("Rate limit exceeded: ip={}, path={}", clientIp, path);
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(429);
                response.getWriter().write(objectMapper.writeValueAsString(
                    ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "请求过于频繁，请稍后再试")));
                return false;
            }
            bucket.tokens--;
        }
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip != null ? ip.split(",")[0].trim() : "unknown";
    }

    /** 令牌桶 */
    private static class TokenBucket {
        int tokens;
        long lastRefillTime;

        TokenBucket(int tokens) {
            this.tokens = tokens;
            this.lastRefillTime = System.currentTimeMillis();
        }
    }
}