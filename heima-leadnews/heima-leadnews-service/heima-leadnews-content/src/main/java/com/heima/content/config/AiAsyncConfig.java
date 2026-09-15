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

    /**
     * Agent 并行工具执行池（多智能体编排专用）。
     *
     * <p>背景：主编 Agent 在单个 ReAct 轮次内可能同时发起多个专家 Worker 调用（如安全/质量/SEO 并行），
     * 各 Worker 内部是一次 LLM 调用（秒级阻塞）。若用 {@link java.util.concurrent.CompletableFuture} 默认
     * ForkJoinPool，会与 SSE 等其它共享任务相互干扰。此处提供独立有界池，实现 Workflow 模式中的 Parallelization。
     */
    @Bean("aiAgentToolExecutor")
    public Executor aiAgentToolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 会话记忆摘要压缩专用池（P2-3b）：单线程低频后台任务，串行化避免并发压缩同一用户会话。
     * 超载丢弃（DiscardPolicy）——压缩是可延迟的优化任务，丢弃后下次 appendTurn 会再次触发，fail-open。
     */
    @Bean("aiMemoryCompressExecutor")
    public Executor aiMemoryCompressExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ai-mem-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}