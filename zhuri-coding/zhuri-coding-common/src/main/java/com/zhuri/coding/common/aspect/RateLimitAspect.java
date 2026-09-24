package com.zhuri.coding.common.aspect;

import com.zhuri.coding.common.annotation.RateLimit;
import com.zhuri.coding.common.exception.RateLimitExceededException;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 限流 AOP 切面
 * 拦截所有标注了 @RateLimit 的方法，逐条检查限流规则
 * 支持通过 @Repeatable 实现多维度独立限流
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;

    /**
     * Lua 脚本缓存
     */
    private static final String LUA_SCRIPT;
    private String luaScriptSha;

    static {
        try {
            ClassPathResource resource = new ClassPathResource("scripts/rate_limit_single.lua");
            LUA_SCRIPT = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("加载限流 Lua 脚本失败", e);
        }
    }

    /**
     * 初始化：预加载脚本到 Redis 提高性能
     */
    @PostConstruct
    public void init() {
        this.luaScriptSha = redissonClient.getScript(StringCodec.INSTANCE).scriptLoad(LUA_SCRIPT);
        log.info("限流 Lua 脚本加载完成, SHA1: {}", luaScriptSha);
    }

    /**
     * 方法级切入点：拦截所有标注了 @RateLimit 或 @RateLimit.Container 的方法
     */
    @Around("@within(com.zhuri.coding.common.annotation.RateLimit) || " +
            "@annotation(com.zhuri.coding.common.annotation.RateLimit) || " +
            "@annotation(com.zhuri.coding.common.annotation.RateLimit.Container)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String className = method.getDeclaringClass().getSimpleName();
        String methodName = method.getName();

        // 获取方法上所有的 @RateLimit 注解（包括 @Repeatable 容器中的）
        RateLimit[] rateLimits = method.getAnnotationsByType(RateLimit.class);
        if (rateLimits.length == 0) {
            return joinPoint.proceed();
        }

        // 逐条检查每条限流规则，任意一条被拒绝即短路返回
        for (RateLimit rateLimit : rateLimits) {
            // 1. 计算时间窗口（毫秒）
            long intervalMs = calculateIntervalMs(rateLimit.interval(), rateLimit.timeUnit());

            // 2. 根据配置维度生成单个 Redis Key
            String key = generateKey(className, methodName, rateLimit.dimension());

            // 3. 调用单 key Lua 脚本执行原子限流
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);

            List<Object> keysList = Collections.singletonList(key);
            Object[] args = {
                    String.valueOf(System.currentTimeMillis()), // ARGV[1]: 当前时间戳
                    String.valueOf(1),                          // ARGV[2]: 申请令牌数（默认1个）
                    String.valueOf(intervalMs),                 // ARGV[3]: 时间窗口
                    String.valueOf(rateLimit.count()),          // ARGV[4]: 最大令牌数
                    UUID.randomUUID().toString()               // ARGV[5]: 请求唯一标识
            };

            Long result;
            try {
                Object resultObj = script.evalSha(
                        RScript.Mode.READ_WRITE,
                        luaScriptSha,
                        RScript.ReturnType.VALUE,
                        keysList,
                        args
                );
                result = convertToLong(resultObj);
            } catch (RedisException e) {
                // NOSCRIPT 处理：Redis 重启后脚本缓存丢失，重新加载并重试
                if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                    log.warn("限流 Lua 脚本缓存丢失，重新加载: {}", e.getMessage());
                    init();
                    Object resultObj = script.evalSha(
                            RScript.Mode.READ_WRITE,
                            luaScriptSha,
                            RScript.ReturnType.VALUE,
                            keysList,
                            args
                    );
                    result = convertToLong(resultObj);
                } else {
                    throw e;
                }
            }

            // 4. 被拒绝则短路，直接执行降级/抛异常
            if (result == null || result == 0) {
                return handleRateLimitExceeded(joinPoint, rateLimit, key);
            }
        }

        // 所有规则都通过，执行原方法
        return joinPoint.proceed();
    }

    /**
     * 计算时间窗口毫秒数
     */
    private long calculateIntervalMs(long interval, RateLimit.TimeUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> interval;
            case SECONDS -> interval * 1000;
            case MINUTES -> interval * 60 * 1000;
            case HOURS -> interval * 3600 * 1000;
            case DAYS -> interval * 86400 * 1000;
        };
    }

    /**
     * 生成单个限流键
     * 使用 Hash Tag ({className:methodName}) 组织同一方法的所有限流 Key
     */
    private String generateKey(String className, String methodName, RateLimit.Dimension dimension) {
        String hashTag = "{" + className + ":" + methodName + "}";
        String keyPrefix = "ratelimit:" + hashTag;

        return switch (dimension) {
            case GLOBAL -> keyPrefix + ":global";
            case IP -> keyPrefix + ":ip:" + getClientIp();
            case USER -> keyPrefix + ":user:" + getCurrentUserId();
        };
    }

    /**
     * 获取客户端真实 IP。
     *
     * <p><b>安全约定</b>：{@code X-Forwarded-For} 是客户端可自行伪造的头。若无条件采信，
     * 攻击者每次请求换一个值即可拿到全新的限流桶，等于让 IP 维度限流失效（登录、短信、上传签名
     * 等防刷场景首当其冲）。因此此处**仅当直连对端是我方受信代理（回环/内网）时**才采信代理头，
     * 且从右往左取第一个非受信地址 —— 我方代理会把观测到的对端追加到最右，最左可能是伪造值。
     */
    private String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }

        HttpServletRequest request = attributes.getRequest();
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = parts[i].trim();
                    if (!candidate.isEmpty() && !isTrustedProxy(candidate)) {
                        return candidate;
                    }
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }

        return remoteAddr != null && !remoteAddr.isEmpty() ? remoteAddr : "unknown";
    }

    /**
     * 是否为受信代理地址（回环 / 内网 / 链路本地）。
     * 需与部署拓扑保持一致：只有来自这些网段的请求才被视为"经过我方代理"。
     */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            int second = secondOctet(ip);
            return second >= 16 && second <= 31;
        }
        return false;
    }

    /** 取 IPv4 第二段（用于判断 172.16.0.0/12 私有网段），非法格式返回 -1 */
    private static int secondOctet(String ip) {
        try {
            String[] parts = ip.split("\\.");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 获取当前用户 ID
     */
    private String getCurrentUserId() {
        try {
            var user = AppThreadLocalUtil.getUser();
            return user != null && user.getId() != null ? String.valueOf(user.getId()) : "anonymous";
        } catch (Exception e) {
            return "anonymous";
        }
    }

    /**
     * 处理限流超出情况
     */
    private Object handleRateLimitExceeded(ProceedingJoinPoint joinPoint, RateLimit rateLimit, String key)
            throws Throwable {
        String methodName = joinPoint.getSignature().getName();

        // 如果配置了降级方法，则调用降级方法
        if (rateLimit.fallback() != null && !rateLimit.fallback().isEmpty()) {
            try {
                Method fallbackMethod = findFallbackMethod(joinPoint, rateLimit.fallback());
                if (fallbackMethod != null) {
                    log.debug("限流触发，执行降级方法: {}.{} -> {}",
                            joinPoint.getTarget().getClass().getSimpleName(),
                            methodName,
                            rateLimit.fallback());
                    // 如果降级方法有参数，传入原方法的参数
                    if (fallbackMethod.getParameterCount() > 0) {
                        return fallbackMethod.invoke(joinPoint.getTarget(), joinPoint.getArgs());
                    } else {
                        return fallbackMethod.invoke(joinPoint.getTarget());
                    }
                }
            } catch (Exception e) {
                log.error("降级方法执行失败: {}", rateLimit.fallback(), e);
            }
        }

        // 没有降级方法或降级失败，抛出限流异常
        log.debug("限流触发，拒绝请求: key={}, dimension={}, count={} per {} {}",
                key, rateLimit.dimension(), rateLimit.count(), rateLimit.interval(), rateLimit.timeUnit());
        throw new RateLimitExceededException("请求过于频繁，请稍后再试");
    }

    /**
     * 查找降级方法
     */
    private Method findFallbackMethod(ProceedingJoinPoint joinPoint, String fallbackName) {
        Class<?> targetClass = joinPoint.getTarget().getClass();
        Method originalMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?>[] parameterTypes = originalMethod.getParameterTypes();

        // 优先查找参数列表一致的方法
        try {
            return targetClass.getDeclaredMethod(fallbackName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            // 忽略，继续查找无参方法
        }

        // 查找无参方法
        try {
            return targetClass.getDeclaredMethod(fallbackName);
        } catch (NoSuchMethodException e) {
            log.error("降级方法未找到: {}.{}", targetClass.getSimpleName(), fallbackName);
            return null;
        }
    }

    /**
     * 将 Redis 返回的对象转换为 Long
     */
    private Long convertToLong(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Long) {
            return (Long) obj;
        }
        if (obj instanceof Integer) {
            return ((Integer) obj).longValue();
        }
        if (obj instanceof String) {
            try {
                return Long.parseLong((String) obj);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}