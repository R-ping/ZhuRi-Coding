package com.zhuri.coding.content.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.dromara.dynamictp.core.support.DynamicTp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 异步执行器配置（SSE 流式问答 / Agent 并行工具 / 记忆压缩 / 审核触发）。
 *
 * <p><b>为什么需要独立线程池</b>：这些任务都是「秒级阻塞的 LLM 调用」，若共用
 * {@link java.util.concurrent.CompletableFuture} 默认的 {@link java.util.concurrent.ForkJoinPool#commonPool()}，
 * 会互相干扰甚至拖垮 JVM 共享池里的其它异步任务。此处按场景隔离出四个有界池。
 *
 * <h3>与动态线程池（dynamic-tp）的结合点</h3>
 *
 * <p>原先四个池的核心参数<b>全部硬编码在代码里</b>，线上要调参必须改代码、重新打包、重启服务。
 * 而线程池恰恰是最需要运行时调参的组件：
 * <ul>
 *   <li>LLM 调用耗时随模型/网络波动，2~4 个线程到底够不够，只有线上才知道；</li>
 *   <li>调大容易、调小难——线程数写死后遇到积压只能干等；</li>
 *   <li>队列水位、拒绝次数这些「线程池是不是已经出问题」的信号，原先完全不可见。</li>
 * </ul>
 *
 * <p>接入方式刻意选择「<b>不改业务代码</b>」：四个池仍在 {@code @Bean} 里用
 * {@link ThreadPoolTaskExecutor} 声明（保留 {@link AppUserTaskDecorator} 等既有语义），
 * 只在 Bean 方法上加 {@link DynamicTp} 交给框架接管——参数改由 Nacos 下发，运行时生效。
 * 对应地，Nacos 配置里必须写 {@code autoCreate: false}，否则框架会再新建一个同名池造成冲突。
 *
 * <p><b>为什么不用 {@code DtpExecutor}</b>：现有代码以 {@code Executor} 类型注入使用，
 * 换成 {@code DtpExecutor} 需要改注入点与类型；而 Spring 的 {@code ThreadPoolTaskExecutor}
 * 本身也实现了 {@code Executor}，交给框架管理即可获得同样的动态能力，改动面最小。
 */
@Configuration
public class AiAsyncConfig {

    /**
     * SSE/流式问答专用线程池。属名 {@code aiSseExecutor} 即 Nacos 里的 {@code threadPoolName}。
     *
     * <p>队列兜底策略：{@link ThreadPoolExecutor.AbortPolicy} —— 流式问答是用户发起的交互请求，
     * 拒绝后由调用方明确告知「繁忙」，比静默排队到超时体验更好。
     */
    @Bean("aiSseExecutor")
    @DynamicTp
    public Executor aiSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 传递请求线程的用户上下文：否则子线程里 AppThreadLocalUtil 为空，
        // 会让 AiLlmGateway 的配额结算（依赖 userId）被静默跳过
        executor.setTaskDecorator(new AppUserTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Agent 并行工具执行池（多智能体编排专用）。
     *
     * <p>主编 Agent 在单个 ReAct 轮次内可能同时发起多个专家 Worker 调用（安全/质量/SEO 并行），
     * 各 Worker 内部是一次 LLM 调用。线程数需随「并行专家数」变化——这正是需要动态调参的典型场景。
     */
    @Bean("aiAgentToolExecutor")
    @DynamicTp
    public Executor aiAgentToolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 传递请求线程的用户上下文：否则子线程里 AppThreadLocalUtil 为空，
        // 会让 AiLlmGateway 的配额结算（依赖 userId）被静默跳过
        executor.setTaskDecorator(new AppUserTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 会话记忆摘要压缩专用池（P2-3b）：单线程低频后台任务，串行化避免并发压缩同一用户会话。
     *
     * <p>超载丢弃（{@link ThreadPoolExecutor.DiscardPolicy}）—— 压缩是可延迟的优化任务，
     * 丢弃后下次 appendTurn 会再次触发，fail-open。
     */
    @Bean("aiMemoryCompressExecutor")
    @DynamicTp
    public Executor aiMemoryCompressExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ai-mem-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 传递请求线程的用户上下文：否则子线程里 AppThreadLocalUtil 为空，
        // 会让 AiLlmGateway 的配额结算（依赖 userId）被静默跳过
        executor.setTaskDecorator(new AppUserTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 评论/沸点审核「尽快执行」触发池。
     *
     * <p>审核任务原先用 {@code CompletableFuture.delayedExecutor(delay, unit)} 触发，
     * 它背后是 {@link java.util.concurrent.ForkJoinPool#commonPool()}；改用三参重载后
     * <b>延迟语义不变，仅把任务体挪到本池</b>。
     *
     * <p>注意：这里只是「尽快执行一次」的优化触发；审核不丢失的真保证是任务落库 +
     * 定时补偿（CommentAuditRecoveryTask / PinsCommentAuditRecoveryTask）+ CAS 抢占。
     * 因此超载拒绝只会让该条审核改由定时补偿拉起，不影响正确性。
     */
    @Bean("aiAuditTriggerExecutor")
    @DynamicTp
    public Executor aiAuditTriggerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-audit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 传递请求线程的用户上下文：否则子线程里 AppThreadLocalUtil 为空，
        // 会让 AiLlmGateway 的配额结算（依赖 userId）被静默跳过
        executor.setTaskDecorator(new AppUserTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
