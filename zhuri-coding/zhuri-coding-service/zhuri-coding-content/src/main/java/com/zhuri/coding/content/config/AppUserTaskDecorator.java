package com.zhuri.coding.content.config;

import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.springframework.core.task.TaskDecorator;

/**
 * 把「当前请求线程的用户上下文」传递给异步任务线程。
 *
 * <p><b>为什么需要</b>：{@link AppThreadLocalUtil} 是 {@link ThreadLocal}，<b>不会跨线程传递</b>。
 * 而 SSE 流式问答、Agent 并行工具、会话记忆压缩等任务都跑在 {@link AiAsyncConfig} 的独立线程池上，
 * 于是子线程里 {@code AppThreadLocalUtil.getUser()} 返回 {@code null}。
 *
 * <p>这会导致一个隐蔽的缺陷：{@code AiLlmGateway.currentUserId()} 依赖该 ThreadLocal，
 * 一旦为 null，{@code settleQuotaByTokens(...)} 里的 {@code if (userId != null)} 不成立，
 * <b>配额结算被静默跳过</b>——表现为「同步问答扣额度、流式问答不扣」。
 *
 * <p>集中在这里修复，而不是让每个异步入口各自 {@code setUser}：后者容易漏（每新增一个
 * 异步入口就要记得补一次），而 TaskDecorator 在提交线程捕获、在执行线程设置，
 * 覆盖使用该线程池的<b>所有</b>任务。
 */
public class AppUserTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 关键：在「提交任务的线程」上捕获上下文（此时请求线程还没返回）
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return runnable;
        }
        return () -> {
            AppThreadLocalUtil.setUser(user);
            try {
                runnable.run();
            } finally {
                // 线程池复用：必须清理，否则下一个任务可能读到上一个请求的用户
                AppThreadLocalUtil.clear();
            }
        };
    }
}
