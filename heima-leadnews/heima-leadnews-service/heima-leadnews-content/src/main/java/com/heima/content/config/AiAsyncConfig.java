package com.heima.content.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 异步执行器配置（SSE 流式问答专用）。
 *
 * <p>背景：AiAsk SSE 接口原先用 {@link java.util.concurrent.CompletableFuture#runAsync(Runnable)}
 * 走公共 ForkJoinPool —— LLM 流式生成是长时间阻塞任务，会拖垮 JVM 共享池里其它并行/异步任务。
 * 此处提供独立有界线程池，将 AI 流式任务与业务异步隔离开。
 */
@Configuration
public class AiAsyncConfig {

    /** SSE/流式问答专用线程池（有界：2 核心 ~ 4 最大 + 50 排队；超载即拒绝，由调用方降级） */
    @Bean("aiSseExecutor")
    public Executor aiSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}