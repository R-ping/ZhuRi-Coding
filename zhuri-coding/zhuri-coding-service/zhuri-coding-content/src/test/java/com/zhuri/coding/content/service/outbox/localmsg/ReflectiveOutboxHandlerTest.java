package com.zhuri.coding.content.service.outbox.localmsg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.content.service.outbox.RetryWithoutCountingException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 反射重放器测试 —— 重点三件事：
 * ① 实参按注解方法的声明类型还原（Long 19 位不丢精度、BigDecimal 保 scale）；
 * ② 业务方法抛出的框架异常（DeadSignal / 不计数重试）必须**剥壳穿透**到 Dispatcher，
 *    否则 Dispatcher 无法精确分类失败；
 * ③ 旧格式 payload 直接判死，不给重试机会。
 */
@DisplayName("注解式本地消息：反射重放器")
class ReflectiveOutboxHandlerTest {

    /** 模拟一个被 @LocalMessage 标注的业务方法（用真实注解，保证反射链路真实） */
    static class SampleService {
        Long capturedId;
        String capturedName;
        BigDecimal capturedAmount;
        RuntimeException toThrow;

        @LocalMessage(eventType = "SAMPLE", key = "'sample:' + #a0")
        public void doBusiness(Long id, String name, BigDecimal amount) {
            this.capturedId = id;
            this.capturedName = name;
            this.capturedAmount = amount;
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    private final SampleService service = new SampleService();

    private ReflectiveOutboxHandler handler() throws Exception {
        return new ReflectiveOutboxHandler("SAMPLE", service,
                SampleService.class.getDeclaredMethod("doBusiness",
                        Long.class, String.class, BigDecimal.class), 0);
    }

    @Test
    @DisplayName("实参按声明类型还原：19 位 Long 不丢精度，BigDecimal 保数值")
    void restoresTypedArgs() throws Exception {
        ReflectiveOutboxHandler handler = handler();
        Long snowflake = 2086403442600767490L;   // 19 位雪花 id：JS Number 会丢精度，Jackson 不会
        BigDecimal amount = new BigDecimal("99.50");

        handler.execute("[2086403442600767490, \"abc\", 99.50]");

        assertEquals(snowflake, service.capturedId);
        assertEquals("abc", service.capturedName);
        assertEquals(0, amount.compareTo(service.capturedAmount));
    }

    @Test
    @DisplayName("业务方法抛 DeadSignal → 剥壳后原样穿透（Dispatcher 才能按类型分类）")
    void deadSignalPassesThrough() throws Exception {
        ReflectiveOutboxHandler handler = handler();
        service.toThrow = new OutboxDispatcher.DeadSignal("业务上永久失败");

        OutboxDispatcher.DeadSignal thrown = assertInstanceOf(OutboxDispatcher.DeadSignal.class,
                assertThrows(Throwable.class, () -> handler.execute("[1, \"x\", 1.00]")));
        assertEquals("业务上永久失败", thrown.getMessage());
    }

    @Test
    @DisplayName("业务方法抛不计数重试异常 → 同样剥壳穿透")
    void retryWithoutCountingPassesThrough() throws Exception {
        ReflectiveOutboxHandler handler = handler();
        service.toThrow = new RetryWithoutCountingException("竞态，等一会儿");

        RetryWithoutCountingException thrown = assertInstanceOf(RetryWithoutCountingException.class,
                assertThrows(Throwable.class, () -> handler.execute("[1, \"x\", 1.00]")));
        assertEquals("竞态，等一会儿", thrown.getMessage());
    }

    @Test
    @DisplayName("旧格式存量 payload（业务 JSON）→ 判死而非重试")
    void legacyPayloadGoesDead() throws Exception {
        ReflectiveOutboxHandler handler = handler();

        // 旧格式是 {"articleId":1} 这种业务 JSON 对象，不是数组
        OutboxDispatcher.DeadSignal thrown = assertInstanceOf(OutboxDispatcher.DeadSignal.class,
                assertThrows(Throwable.class, () -> handler.execute("{\"articleId\":1}")));
        assertEquals(true, thrown.getMessage().contains("旧格式存量"));
    }

    @Test
    @DisplayName("实参个数与注解方法不符 → 判死")
    void argCountMismatchGoesDead() throws Exception {
        ReflectiveOutboxHandler handler = handler();

        // 注解方法要 3 个参数，只给了 2 个
        OutboxDispatcher.DeadSignal thrown = assertInstanceOf(OutboxDispatcher.DeadSignal.class,
                assertThrows(Throwable.class, () -> handler.execute("[1, \"x\"]")));
        assertEquals(true, thrown.getMessage().contains("实参个数"));
    }

    @Test
    @DisplayName("args 数组按位置对应参数（多参数不串位）")
    void argsMapByPosition() throws Exception {
        ReflectiveOutboxHandler handler = handler();
        List<String> payload = List.of("[55, \"name-55\", 12.34]");
        handler.execute(payload.get(0));

        assertEquals(55L, service.capturedId);
        assertEquals("name-55", service.capturedName);
        assertEquals(0, new BigDecimal("12.34").compareTo(service.capturedAmount));
    }
}
