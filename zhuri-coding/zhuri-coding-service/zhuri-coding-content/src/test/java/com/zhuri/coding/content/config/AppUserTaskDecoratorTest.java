package com.zhuri.coding.content.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用户上下文跨线程传递装饰器单测。
 *
 * <p>守护点：SSE / Agent / 记忆压缩都跑在独立线程池上，而 ThreadLocal 不跨线程。
 * 若这里失效，{@code AiLlmGateway} 在子线程取不到 userId，<b>流式路径的配额结算会被静默跳过</b>
 * （表现为"同步问答扣额度、流式问答不扣"），所以必须有测试守住。
 */
@DisplayName("用户上下文跨线程传递（AppUserTaskDecorator）")
class AppUserTaskDecoratorTest {

    @Test
    @DisplayName("提交线程有用户 → 任务执行线程仍能读到该用户")
    void propagatesUserToTaskThread() {
        ApUser user = new ApUser();
        user.setId(9);
        AppThreadLocalUtil.setUser(user);

        AtomicReference<Integer> seen = new AtomicReference<>();
        Runnable decorated = new AppUserTaskDecorator().decorate(() -> {
            ApUser inTask = AppThreadLocalUtil.getUser();
            seen.set(inTask == null ? null : inTask.getId());
        });

        // 模拟"另一个线程执行"：先清掉提交线程的上下文，再运行被装饰的任务
        AppThreadLocalUtil.clear();
        decorated.run();

        assertEquals(9, seen.get(), "子线程应能读到提交线程捕获的用户上下文");
    }

    @Test
    @DisplayName("任务执行完毕后清理上下文（线程池复用不得串号）")
    void clearsContextAfterTask() {
        ApUser user = new ApUser();
        user.setId(9);
        AppThreadLocalUtil.setUser(user);

        Runnable decorated = new AppUserTaskDecorator().decorate(() -> {
            // 任务体内应能看到用户
        });
        AppThreadLocalUtil.clear();
        decorated.run();

        assertNull(AppThreadLocalUtil.getUser(), "任务结束后必须清理，否则下一个任务会串到上一个请求的用户");
    }

    @Test
    @DisplayName("提交线程无用户 → 原样返回，不做多余包装")
    void noUserReturnsOriginalRunnable() {
        AppThreadLocalUtil.clear();
        Runnable original = () -> {
        };

        assertSame(original, new AppUserTaskDecorator().decorate(original));
    }

    @Test
    @DisplayName("任务抛异常时上下文同样被清理")
    void clearsContextOnException() {
        ApUser user = new ApUser();
        user.setId(9);
        AppThreadLocalUtil.setUser(user);
        Runnable decorated = new AppUserTaskDecorator().decorate(() -> {
            throw new IllegalStateException("boom");
        });
        AppThreadLocalUtil.clear();

        try {
            decorated.run();
        } catch (IllegalStateException ignore) {
            // 预期
        }

        assertNull(AppThreadLocalUtil.getUser());
    }
}
