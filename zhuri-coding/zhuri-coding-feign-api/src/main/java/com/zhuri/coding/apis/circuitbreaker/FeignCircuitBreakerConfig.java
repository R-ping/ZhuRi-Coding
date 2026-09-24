package com.zhuri.coding.apis.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 熔断/超时的**统一默认策略**。
 *
 * <p><b>为什么必须有这个类</b>：开启 {@code spring.cloud.openfeign.circuitbreaker.enabled=true} 后，
 * Spring Cloud CircuitBreaker(Resilience4j) 会用 {@code TimeLimiterRegistry.ofDefaults()} 包装每次
 * Feign 调用，而 Resilience4j 的 TimeLimiter 默认超时是 <b>1 秒</b>。若不显式覆盖，就会在全仓所有
 * 内网 Feign 调用上凭空多出一个 1s 上限——一旦某个读接口偶发超过 1s，就会直接走 fallback 降级，
 * 属于"配置默认值造成的静默行为变化"。此处把策略显式化。
 *
 * <p><b>三层超时的分工</b>（避免互相打架）：
 * <ol>
 *   <li><b>Feign 自身</b> connect/read timeout —— 第一道、且可按客户端精确设置
 *       （见各服务 yml {@code spring.cloud.openfeign.client.config.*}）；</li>
 *   <li><b>TimeLimiter</b>（本类）—— 兜底，设得比 Feign 读超时更长，用于应对 Feign 超时未覆盖的
 *       挂死场景（如 SSL 握手卡住）；</li>
 *   <li><b>CircuitBreaker</b>（本类）—— 故障期内不再发请求，避免每个请求都白等一轮超时。</li>
 * </ol>
 *
 * <p><b>放置位置</b>：本类在 feign-api 模块内，而各服务均以
 * {@code @SpringBootApplication(scanBasePackages = "com.zhuri.coding")} 启动，
 * 因此五个服务都会自动加载这一份共享配置，无需逐服务重复声明。
 *
 * <p><b>与自研 {@code AiCircuitBreaker} 的关系</b>：两者并存、职责不同——
 * 本类管"跨服务 Feign 调用"，{@code AiCircuitBreaker} 管"LLM / Embedding / search 三类外部依赖"
 * 并把状态暴露在 {@code /ai/metrics} 供观测。口径不同是有意为之（自研那套是按 RAG 链路定制的
 * 5 次 / 60s 窗口），此处不做合并以免改动面过大。
 */
@Configuration
public class FeignCircuitBreakerConfig {

    /** TimeLimiter 兜底超时：需**明显长于**各客户端 Feign 读超时，否则会抢在 Feign 超时前切断正常慢请求 */
    private static final Duration TIME_LIMITER_TIMEOUT = Duration.ofSeconds(3);

    /** 统计窗口大小（按调用次数滑动） */
    private static final int SLIDING_WINDOW_SIZE = 20;

    /** 窗口内最少调用数：样本太少不判定，避免启动初期抖动就把熔断打开 */
    private static final int MINIMUM_NUMBER_OF_CALLS = 10;

    /** 失败率阈值（%）：窗口内失败占比达到即打开 */
    private static final float FAILURE_RATE_THRESHOLD = 50f;

    /** 打开时长：到期后进入半开放行少量试探 */
    private static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(10);

    /** 半开态允许的试探调用数 */
    private static final int PERMITTED_CALLS_IN_HALF_OPEN = 3;

    /**
     * 默认策略：对所有未单独配置的 Feign 客户端生效（每个客户端一个独立熔断器实例）。
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> feignCircuitBreakerDefaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
            .circuitBreakerConfig(CircuitBreakerConfig.custom()
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(WAIT_DURATION_IN_OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN)
                .build())
            .timeLimiterConfig(TimeLimiterConfig.custom()
                .timeoutDuration(TIME_LIMITER_TIMEOUT)
                .build())
            .build());
    }
}
