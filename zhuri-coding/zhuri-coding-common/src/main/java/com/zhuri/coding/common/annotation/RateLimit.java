package com.heima.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式限流注解
 * 用于方法级别的限流控制，支持通过 @Repeatable 实现多维度独立限流
 *
 * 使用示例：
 * <pre>{@code
 * // 全局限流 + IP 限流，各自独立计数
 * @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 100)
 * @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
 * public Result query() { ... }
 *
 * // 带降级方法
 * @RateLimit(dimension = Dimension.GLOBAL, count = 10, fallback = "fallbackMethod")
 * public Result create() { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.Container.class)
public @interface RateLimit {

    /**
     * 限流维度枚举
     */
    enum Dimension {
        /**
         * 全局限流：对所有请求统一限流
         */
        GLOBAL,
        /**
         * IP限流：按客户端IP地址限流
         */
        IP,
        /**
         * 用户限流：按用户ID限流
         */
        USER
    }

    /**
     * 限流维度配置
     * 每条注解配置一个维度，多条注解各自独立限流
     */
    Dimension dimension() default Dimension.GLOBAL;

    /**
     * 在指定时间窗口内允许的最大请求数
     * 例如：count = 10, interval = 1, timeUnit = SECONDS 表示每秒最多 10 次
     */
    double count();

    /**
     * 时间窗口大小，默认 1
     */
    long interval() default 1;

    /**
     * 时间单位，默认为秒
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 等待令牌的超时时间
     * 0 表示不等待，直接获取令牌，失败则拒绝
     */
    long timeout() default 0;

    /**
     * 降级方法名
     * 支持无参方法或与原方法参数列表一致的方法
     */
    String fallback() default "";

    /**
     * 时间单位枚举
     */
    enum TimeUnit {
        MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS
    }

    /**
     * @Repeatable 容器注解
     * 当同一方法上标注多个 @RateLimit 时，Java 编译器自动生成此容器
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Container {
        RateLimit[] value();
    }
}