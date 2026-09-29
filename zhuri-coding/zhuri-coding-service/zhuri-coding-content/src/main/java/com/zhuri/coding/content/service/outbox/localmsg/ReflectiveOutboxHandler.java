package com.zhuri.coding.content.service.outbox.localmsg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.content.service.outbox.OutboxHandler;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessageReplayContext.ThrowingRunnable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 把一条 Outbox 事件「重放」回被 {@link LocalMessage} 标注的业务方法。
 *
 * <p><b>payload 的格式</b>：JSON 数组，按位置对应注解方法的实参。
 * 例：{@code publishArticle(Long articleId)} → {@code [123]}。
 * <b>不包含类名与方法名</b>——方法绑定来自启动时的注解注册表，改方法名只需重新部署，
 * 已落库数据里没有任何会随重构失效的东西。
 *
 * <p><b>两处刻意设计</b>：
 * <ul>
 *   <li>重放必须<b>走代理</b>（bean 是切面拦截到的那个代理对象）：既让「防重入」标记生效放行真正的执行，
 *       也不绕过方法上可能存在的其他切面语义；</li>
 *   <li>反射抛出的异常要<b>剥壳还原</b>（{@link InvocationTargetException#getCause()}），
 *       否则 Dispatcher 看到的永远是包装异常，无法按 {@code DeadSignal} / {@code RetryWithoutCountingException}
 *       精确分类失败。</li>
 * </ul>
 */
public class ReflectiveOutboxHandler implements OutboxHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String eventType;
    private final Object bean;
    private final Method method;
    private final int lifetimeMinutes;

    public ReflectiveOutboxHandler(String eventType, Object bean, Method method, int lifetimeMinutes) {
        this.eventType = eventType;
        this.bean = bean;
        this.method = method;
        this.lifetimeMinutes = lifetimeMinutes;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public int maxLifetimeMinutes() {
        return lifetimeMinutes;
    }

    @Override
    public void execute(String payload) throws Exception {
        JsonNode argsNode = OBJECT_MAPPER.readTree(payload);
        if (argsNode == null || !argsNode.isArray()) {
            // 旧格式存量（业务 JSON）：重试也不可能成功，直接进死信留一句能看懂的原因
            throw new OutboxDispatcher.DeadSignal("payload 不是实参数组（旧格式存量，需人工处理）: " + payload);
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (argsNode.size() != paramTypes.length) {
            throw new OutboxDispatcher.DeadSignal(
                    "实参个数与注解方法不符: payload=" + payload + ", 期望 " + paramTypes.length + " 个参数");
        }
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            // Long 按 Jackson 的整数节点还原，19 位雪花 id 不会丢精度（精度丢失只发生在 JS Number）
            args[i] = OBJECT_MAPPER.treeToValue(argsNode.get(i), paramTypes[i]);
        }

        LocalMessageReplayContext.runAsReplaying((ThrowingRunnable) () -> {
            try {
                method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // 剥壳：把业务方法抛出的原始异常（含 DeadSignal / RetryWithoutCountingException）交回 Dispatcher
                throw e.getCause() == null ? e : e.getCause();
            }
        });
    }
}
