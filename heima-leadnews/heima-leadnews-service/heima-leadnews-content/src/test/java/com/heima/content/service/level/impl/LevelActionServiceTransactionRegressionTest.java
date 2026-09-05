package com.heima.content.service.level.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * LevelActionService 事务自调用陷阱回归测试。
 *
 * <p>背景：recordActionWithLimit 曾存在 Spring 事务自调用陷阱 —— 3 参 public 入口（无注解）
 * this 自调用 4 参 protected 重载（注解在重载上），同类自调用不经过 AOP 代理导致 @Transactional
 * 永不生效：悲观行锁 FOR UPDATE 在 autocommit 下随语句结束立即释放（TOCTOU 超限刷分窗口打开），
 * 行为日志/掘友分明细/汇总/等级多表写入失去原子性。
 *
 * <p>回归断言：@Transactional 必须位于外部可见的 public 入口方法（被 Spring 代理拦截），
 * 4 参实现必须是 private（仅内部调用，杜绝再次把注解放回不可代理位置）。
 * 修复前注解在 protected 重载上 → 断言失败；修复后注解在 public 入口 + private 内部 → 通过。
 */
@SpringBootTest
class LevelActionServiceTransactionRegressionTest {

    @Autowired
    private LevelActionService levelActionService;

    /** 屏蔽外部副作用与消费者线程，仅验证 Bean 装配与注解结构（与 ArticleCommentE2ETest 同款隔离） */
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private RedissonClient redissonClient;

    @MockBean
    private com.heima.content.schedule.listener.RedissonDelayQueue redissonDelayQueue;

    @MockBean
    private com.heima.content.service.order.impl.OrderTimeoutTask orderTimeoutTask;

    @Test
    @DisplayName("回归：@Transactional 必须标注在 public 入口而非内部重载（防事务自调用复发）")
    void transactionalMustBeOnPublicEntry() {
        // Spring CGLIB 代理类名带 $$EnhancerBySpringCGLIB$$，需取父类获取原始声明的方法
        Class<?> targetClass = levelActionService.getClass();
        if (targetClass.getName().contains("$$")) {
            targetClass = targetClass.getSuperclass();
        }

        Method entry = findMethod(targetClass, "recordActionWithLimit", Long.class, String.class, String.class);
        Method internal = findMethod(targetClass, "recordActionWithLimitInternal",
            Long.class, String.class, BigDecimal.class, String.class);

        assertThat(entry)
            .as("3 参 public 入口方法必须存在（外部唯一可见调用点）")
            .isNotNull();
        assertThat(internal)
            .as("4 参实现应改为 private 内部方法（仅被 public 入口 this 调用）")
            .isNotNull();
        assertThat(java.lang.reflect.Modifier.isPrivate(internal.getModifiers()))
            .as("4 参实现必须是 private，避免外部绕过 public 入口的事务边界")
            .isTrue();
        assertThat(entry.getAnnotation(Transactional.class))
            .as("@Transactional 必须标注在 public 入口上 —— 同类 this 自调用不经过 AOP 代理，"
                + "注解放在内部方法上会静默失效（悲观行锁与多表写入失去事务保护）")
            .isNotNull();
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
