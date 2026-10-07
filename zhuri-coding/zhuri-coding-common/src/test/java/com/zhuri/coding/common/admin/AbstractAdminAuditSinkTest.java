package com.zhuri.coding.common.admin;

import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbstractAdminAuditSink} 公共骨架测试。
 *
 * <p>重点不在"有没有调 insert"，而在两件容易被顺手改坏、改坏以后很久才发现的事：
 * <ol>
 *   <li><b>事务传播</b>：成功走 {@code REQUIRED}（与业务同生共死）、失败走 {@code REQUIRES_NEW}
 *       （业务回滚了失败记录也要留下）。这两条只能靠反射钉住 —— 单测跑不出事务语义，
 *       而一旦被改成一样，"被拒绝的操作没留痕"这种问题要到线上查争议时才会暴露。</li>
 *   <li><b>失败路径必须吞掉自身异常</b>：业务错误比审计错误重要，审计写不进去不能盖住原始错误。</li>
 * </ol>
 */
@DisplayName("运营审计公共骨架")
class AbstractAdminAuditSinkTest {

    /** 可编程的测试实现：记录所有插入，并可按需让插入抛异常 */
    private static class RecordingSink extends AbstractAdminAuditSink {

        final List<ApAdminAuditLog> inserted = new ArrayList<>();

        RuntimeException failWith;

        @Override
        protected void insert(ApAdminAuditLog entry) {
            if (failWith != null) {
                throw failWith;
            }
            inserted.add(entry);
        }
    }

    private final RecordingSink sink = new RecordingSink();

    private static ApAdminAuditLog entry() {
        return AdminAuditSink.entry(ApAdminAuditLog.MODULE_USER, "USER_BAN", "USER", "1001", "恶意刷屏");
    }

    @AfterEach
    void clearContext() {
        AdminContext.clear();
    }

    @Nested
    @DisplayName("成功路径")
    class Success {

        @Test
        @DisplayName("补结果与时间后落库；error_msg 被清空（避免复用入参时残留上一次的错误）")
        void fillsResultAndTime() {
            ApAdminAuditLog audit = entry();
            audit.setErrorMsg("上一次的残留");

            sink.recordSuccess(audit);

            assertEquals(1, sink.inserted.size());
            ApAdminAuditLog saved = sink.inserted.get(0);
            assertSame(audit, saved, "必须是同一个对象，审计表内容与调用方看到的要一致");
            assertEquals(ApAdminAuditLog.RESULT_SUCCESS, saved.getResult());
            assertNull(saved.getErrorMsg(), "成功记录不该带着失败原因");
            assertNotNull(saved.getCreatedTime());
        }

        @Test
        @DisplayName("从 AdminContext 补齐操作人与角色快照（角色可能事后被回收，必须固化当时身份）")
        void fillsOperatorFromContext() {
            AdminContext.set(new AdminIdentity(7, List.of("SUPER_ADMIN")));

            sink.recordSuccess(entry());

            ApAdminAuditLog saved = sink.inserted.get(0);
            assertEquals(7, saved.getUserId());
            assertEquals("SUPER_ADMIN", saved.getRoleCodes());
        }

        @Test
        @DisplayName("无运营上下文时留空而不报错（异步线程 / 单测里没有上下文是正常情况）")
        void toleratesMissingContext() {
            sink.recordSuccess(entry());

            assertNull(sink.inserted.get(0).getUserId());
            assertNull(sink.inserted.get(0).getRoleCodes());
        }

        @Test
        @DisplayName("插入失败必须抛出去 —— fail-closed，宁可业务回滚也不能有变更而无留痕")
        void insertFailurePropagates() {
            sink.failWith = new IllegalStateException("Table 'ap_admin_audit_log' doesn't exist");

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> sink.recordSuccess(entry()));
            assertTrue(e.getMessage().contains("doesn't exist"));
        }
    }

    @Nested
    @DisplayName("失败路径")
    class Failure {

        @Test
        @DisplayName("记 result=0 与失败原因")
        void recordsFailure() {
            sink.recordFailure(entry(), "该账号正在封禁中，无需重复警告");

            ApAdminAuditLog saved = sink.inserted.get(0);
            assertEquals(ApAdminAuditLog.RESULT_FAIL, saved.getResult());
            assertEquals("该账号正在封禁中，无需重复警告", saved.getErrorMsg());
        }

        @Test
        @DisplayName("插入异常必须被吞掉：业务已经失败，不能再用审计错误盖住真正的失败原因")
        void swallowsInsertFailure() {
            sink.failWith = new RuntimeException("表不存在");

            assertDoesNotThrow(() -> sink.recordFailure(entry(), "原始业务错误"));
        }

        @Test
        @DisplayName("失败原因超长按列宽截断到 500（否则 Data too long 会让整条记录写不进去）")
        void truncatesErrorMsg() {
            sink.recordFailure(entry(), "x".repeat(600));

            assertEquals(500, sink.inserted.get(0).getErrorMsg().length());
        }

        @Test
        @DisplayName("errorMsg 为 null 不抛异常（NPE 会让本该留下的失败记录消失）")
        void toleratesNullErrorMsg() {
            assertDoesNotThrow(() -> sink.recordFailure(entry(), null));
            assertNull(sink.inserted.get(0).getErrorMsg());
        }
    }

    @Nested
    @DisplayName("事务语义（反射钉住，改坏不会立刻暴露）")
    class TransactionSemantics {

        private Method method(String name, Class<?>... params) {
            try {
                return AbstractAdminAuditSink.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("找不到方法 " + name, e);
            }
        }

        private Transactional annotationOf(Method method) {
            Transactional annotation = method.getAnnotation(Transactional.class);
            assertNotNull(annotation, method.getName() + " 必须标 @Transactional");
            return annotation;
        }

        @Test
        @DisplayName("recordSuccess = REQUIRED：与业务同事务，不会出现「改了内容但查不到是谁做的」")
        void successIsRequired() {
            Method m = method("recordSuccess", ApAdminAuditLog.class);

            assertEquals(Propagation.REQUIRED, annotationOf(m).propagation());
        }

        @Test
        @DisplayName("recordFailure = REQUIRES_NEW：业务事务注定回滚，失败记录必须独立提交")
        void failureIsRequiresNew() {
            Method m = method("recordFailure", ApAdminAuditLog.class, String.class);

            assertEquals(Propagation.REQUIRES_NEW, annotationOf(m).propagation());
        }

        @Test
        @DisplayName("两个方法都必须 rollbackFor=Exception：默认只对 RuntimeException 回滚，审计异常多是受检的 SQLException 包装")
        void bothRollbackForException() {
            for (Method m : Arrays.asList(
                method("recordSuccess", ApAdminAuditLog.class),
                method("recordFailure", ApAdminAuditLog.class, String.class))) {
                assertArrayEquals(new Class<?>[]{Exception.class}, annotationOf(m).rollbackFor(), m.getName());
            }
        }
    }

    @Nested
    @DisplayName("AdminAuditSink.entry 工厂")
    class EntryFactory {

        @Test
        @DisplayName("只填业务事实，环境事实（谁/何时/IP/结果）留给实现补齐")
        void fillsBusinessFactsOnly() {
            ApAdminAuditLog audit = entry();

            assertEquals(ApAdminAuditLog.MODULE_USER, audit.getModule());
            assertEquals("USER_BAN", audit.getAction());
            assertEquals("USER", audit.getTargetType());
            assertEquals("1001", audit.getTargetId());
            assertEquals("恶意刷屏", audit.getReason());
            assertNull(audit.getUserId(), "操作人由实现从 AdminContext 补，调用方不该自己填");
            assertNull(audit.getCreatedTime());
            assertNull(audit.getResult(), "结果由实现按成功/失败填");
        }

        @Test
        @DisplayName("createdTime 由实现补 —— 调用方传入的时间可能是构造 DTO 的时间，不是写库时间")
        void createdTimeFilledByImpl() {
            sink.recordSuccess(entry());

            Date createdTime = sink.inserted.get(0).getCreatedTime();
            assertNotNull(createdTime);
            assertTrue(Math.abs(System.currentTimeMillis() - createdTime.getTime()) < 10_000);
        }
    }
}
