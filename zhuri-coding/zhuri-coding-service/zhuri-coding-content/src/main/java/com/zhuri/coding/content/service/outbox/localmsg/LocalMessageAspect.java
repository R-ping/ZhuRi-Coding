package com.zhuri.coding.content.service.outbox.localmsg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.outbox.OutboxService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * {@link LocalMessage} 的切面：把「业务事务内的一次方法调用」变成「一条本地消息 + 稍后的重放」。
 *
 * <p>分三种情况：
 * <ol>
 *   <li><b>正在重放</b>（{@link LocalMessageReplayContext#isReplaying()}）→ 直接放行执行方法体，
 *       不再存档 —— 否则重放会被再次存档，无限循环；</li>
 *   <li><b>在业务事务里</b>（常态）→ 只存档、不执行方法体，随业务同事务提交，
 *       由 {@code OutboxDispatcher} 在提交后重放；</li>
 *   <li><b>不在事务里</b> → 存档后<b>立即执行一次</b> —— 没有"提交后"这个时点可等，等就是白等。</li>
 * </ol>
 *
 * <p>存档复用 {@link OutboxService#record}：幂等键冲突静默短路、与业务同事务提交，
 * 一切与显式调用时完全一致。
 */
@Aspect
@Component
@Slf4j
public class LocalMessageAspect {

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OutboxService outboxService;

    public LocalMessageAspect(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Around("@annotation(localMessage)")
    public Object around(ProceedingJoinPoint joinPoint, LocalMessage localMessage) throws Throwable {
        // ① 重放中：放行，真正执行方法体
        if (LocalMessageReplayContext.isReplaying()) {
            return joinPoint.proceed();
        }

        // ② 业务调用：存档（事件类型 + 实参数组 + 本方法声明的重试预算），不执行
        Object[] args = joinPoint.getArgs();
        String payload = OBJECT_MAPPER.writeValueAsString(args);
        String eventKey = evalKey(localMessage.key(), args, localMessage.eventType());
        outboxService.record(eventKey, localMessage.eventType(), payload, localMessage.maxRetries());

        // ③ 无事务：没有「提交后」这个时点可等，存档后立即执行一次
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.info("[LocalMessage] 无事务上下文，{} 立即执行: eventKey={}",
                    localMessage.eventType(), eventKey);
            return joinPoint.proceed();
        }

        // 事务内：吞掉这次调用，等 Dispatcher 在提交后重放
        // 返回 null 只对 void 方法成立（注册表在启动时已校验并拒绝非 void）
        return null;
    }

    /** 幂等键 SpEL：#a0 / #a1… 按位置引用实参（不依赖编译期参数名） */
    private String evalKey(String keyExpression, Object[] args, String eventType) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            context.setVariable("a" + i, args[i]);
        }
        String key = SPEL_PARSER.parseExpression(keyExpression).getValue(context, String.class);
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("幂等键 SpEL 求值结果为空: eventType=" + eventType
                    + ", key=" + keyExpression);
        }
        return key;
    }
}
