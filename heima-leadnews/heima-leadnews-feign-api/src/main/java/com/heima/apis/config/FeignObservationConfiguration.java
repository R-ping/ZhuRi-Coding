package com.heima.apis.config;

import feign.micrometer.MicrometerObservationCapability;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 Feign 可观测性配置。
 *
 * <p>Spring Cloud OpenFeign 4.1+ 已移除内置的 FeignObservationAutoConfiguration，
 * 无法单纯通过配置属性开启跨服务调用追踪。此处将
 * {@link MicrometerObservationCapability}（来自 feign-micrometer 模块）以
 * {@link FeignBuilderCustomizer} 的形式全局注册到所有 Feign 客户端：
 * <ul>
 *   <li>Feign 调用基于 Micrometer Observation 生成 CLIENT span；</li>
 *   <li>traceId/spanId 通过 HTTP 头向下游服务传播，使整条调用链归属同一 trace；</li>
 *   <li>配合 management.tracing（Brave + Zipkin）完成链路上报。</li>
 * </ul>
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MicrometerObservationCapability.class)
public class FeignObservationConfiguration {

    /**
     * 注册全局 Feign 观测能力 Bean。
     *
     * @param observationRegistry Boot 自动装配的 ObservationRegistry（由 micrometer-observation 提供）
     * @return 应用于所有 Feign 客户端的 Builder 定制器
     */
    @Bean
    @ConditionalOnMissingBean
    public FeignBuilderCustomizer feignObservationCustomizer(ObservationRegistry observationRegistry) {
        // builder.addCapability 注册后，ObservedFeignClient 会在调用前后创建/结束 HTTP 客户端观测 span，
        // 并借助 Micrometer Tracing 的传播机制把 trace 上下文写入下游请求头。
        return builder -> builder.addCapability(new MicrometerObservationCapability(observationRegistry));
    }
}