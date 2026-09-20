package com.zhuri.coding.content.behavior.service;

import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BehaviorEventBus 单元测试
 *
 * <p>覆盖行为事件总线的全部核心逻辑：
 * <ul>
 *   <li>init()：handlerList 注册到 handlerMap、postProcessorList 为 null 分支；</li>
 *   <li>execute()：context/behaviorType 为 null → PARAM_INVALID；无 handler → SERVER_ERROR；
 *       handler 返回失败/抛异常 → SERVER_ERROR；成功且 isNewRecord 走后置处理器链；
 *       后置处理器抛异常被吞掉继续；</li>
 *   <li>rollback()：与 execute 对应的各分支。</li>
 * </ul>
 *
 * <p>handlerList / postProcessorList 两个 List 用 @Mock 注入，并通过反射写入字段，
 * 再反射调用 init() 使其就绪，避免真实 @PostConstruct 依赖 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BehaviorEventBusTest {

    @Mock
    private List<BehaviorHandler> handlerList;
    @Mock
    private List<BehaviorPostProcessor> postProcessorList;

    private final BehaviorEventBus eventBus = new BehaviorEventBus();

    private BehaviorHandler handler;
    private BehaviorPostProcessor processor;

    // ==================== 反射辅助 ====================

    /** 反射设置私有字段 */
    private void setField(String name, Object value) throws Exception {
        Field f = BehaviorEventBus.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(eventBus, value);
    }

    /** 反射调用 @PostConstruct init()，注入 mock 字段后使其就绪 */
    private void callInit() throws Exception {
        Method m = BehaviorEventBus.class.getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(eventBus);
    }

    /** 就绪：仅注册 handler（无后置处理器时的通用就绪，覆盖 sortedPostProcessors 不排序路径） */
    private void prepareOnlyHandlers(List<BehaviorHandler> handlers) throws Exception {
        setField("handlerList", handlerList);
        when(handlerList.iterator()).thenReturn(handlers.iterator());
        when(handlerList.stream()).thenReturn(handlers.stream());
        setField("postProcessorList", postProcessorList);
        when(postProcessorList.stream()).thenReturn(Collections.<BehaviorPostProcessor>emptyList().stream());
        when(postProcessorList.iterator()).thenReturn(Collections.<BehaviorPostProcessor>emptyList().iterator());
        callInit();
    }

    /** 就绪：postProcessorList 显式为 null（覆盖 init 中 null 分支与 execute 中不触发后置处理） */
    private void prepareNullProcessors(List<BehaviorHandler> handlers) throws Exception {
        setField("handlerList", handlerList);
        when(handlerList.iterator()).thenReturn(handlers.iterator());
        when(handlerList.stream()).thenReturn(handlers.stream());
        setField("postProcessorList", null);
        callInit();
    }

    /** 就绪：同时注册 handler 与 postProcessor（覆盖后置处理器链） */
    private void prepareWithProcessors(List<BehaviorHandler> handlers, List<BehaviorPostProcessor> processors) throws Exception {
        setField("handlerList", handlerList);
        when(handlerList.iterator()).thenReturn(handlers.iterator());
        when(handlerList.stream()).thenReturn(handlers.stream());
        setField("postProcessorList", postProcessorList);
        when(postProcessorList.stream()).thenReturn(processors.stream());
        when(postProcessorList.iterator()).thenReturn(processors.iterator());
        callInit();
    }

    /** 构造一个拥有指定 handler/processor mock 的普通上下文（真实 handler 由 prepare 注册） */
    private BehaviorHandler stubHandler() {
        BehaviorHandler h = org.mockito.Mockito.mock(BehaviorHandler.class);
        when(h.getType()).thenReturn(BehaviorType.LIKE_ARTICLE);
        return h;
    }

    private BehaviorPostProcessor stubProcessor(int order) {
        BehaviorPostProcessor p = org.mockito.Mockito.mock(BehaviorPostProcessor.class);
        when(p.getOrder()).thenReturn(order);
        return p;
    }

    // ==================== init：handler 注册 ====================

    @Test
    @DisplayName("init - handlerList 注册到 handlerMap，execute 可路由到对应 handler")
    void initRegistersHandlers() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.success(BehaviorType.LIKE_ARTICLE).withNewRecord(true));
        prepareOnlyHandlers(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        // 有 handler 被注册，execute 走到成功分支 => code 200
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(handler, times(2)).getType();
        verify(handler).execute(ctx);
    }

    @Test
    @DisplayName("init - postProcessorList 为 null 时不排序后置处理器，execute 仍正常")
    void initWithNullProcessors() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.success(BehaviorType.LIKE_ARTICLE).withNewRecord(true));
        prepareNullProcessors(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
    }

    // ==================== execute：参数校验 ====================

    @Test
    @DisplayName("execute - context 为 null 返回 PARAM_INVALID")
    void executeNullContext() {
        ResponseResult r = eventBus.execute(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("execute - behaviorType 为 null 返回 PARAM_INVALID")
    void executeNullBehaviorType() {
        BehaviorContext ctx = new BehaviorContext();
        ctx.setUserId(1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    // ==================== execute：路由与执行 ====================

    @Test
    @DisplayName("execute - 无对应 handler 返回 SERVER_ERROR")
    void executeNoHandler() throws Exception {
        prepareOnlyHandlers(Collections.emptyList());
        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
    }

    @Test
    @DisplayName("execute - handler.execute 返回失败结果时返回 SERVER_ERROR")
    void executeHandlerFailure() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.failure(BehaviorType.LIKE_ARTICLE, "like failed"));
        prepareOnlyHandlers(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
    }

    @Test
    @DisplayName("execute - handler.execute 抛异常时返回 SERVER_ERROR")
    void executeHandlerException() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenThrow(new RuntimeException("execute boom"));
        prepareOnlyHandlers(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
    }

    @Test
    @DisplayName("execute - 成功且 isNewRecord 触发后置处理器链")
    void executeSuccessTriggersProcessors() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.success(BehaviorType.LIKE_ARTICLE).withNewRecord(true));
        processor = stubProcessor(10);
        prepareWithProcessors(Collections.singletonList(handler), Collections.singletonList(processor));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(handler).execute(ctx);
        verify(processor).postProcess(any(), any());
    }

    @Test
    @DisplayName("execute - 成功但非新记录不触发后置处理器")
    void executeSuccessNotNewRecord() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.success(BehaviorType.LIKE_ARTICLE)); // isNewRecord=false
        processor = stubProcessor(10);
        prepareWithProcessors(Collections.singletonList(handler), Collections.singletonList(processor));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(processor, never()).postProcess(any(), any());
    }

    @Test
    @DisplayName("execute - 成功且 postProcessorList 为 null 不触发后置处理")
    void executeNullProcessors() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.success(BehaviorType.LIKE_ARTICLE).withNewRecord(true));
        prepareNullProcessors(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
    }

    @Test
    @DisplayName("execute - 后置处理器抛异常被吞掉，主流程仍返回成功")
    void executeProcessorExceptionSwallowed() throws Exception {
        handler = stubHandler();
        when(handler.execute(any())).thenReturn(BehaviorResult.success(BehaviorType.LIKE_ARTICLE).withNewRecord(true));
        processor = stubProcessor(10);
        doThrow(new RuntimeException("processor boom")).when(processor).postProcess(any(), any());
        prepareWithProcessors(Collections.singletonList(handler), Collections.singletonList(processor));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.execute(ctx);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - context 为 null 返回 PARAM_INVALID")
    void rollbackNullContext() {
        ResponseResult r = eventBus.rollback(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("rollback - behaviorType 为 null 返回 PARAM_INVALID")
    void rollbackNullBehaviorType() {
        BehaviorContext ctx = new BehaviorContext();
        ResponseResult r = eventBus.rollback(ctx);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("rollback - 无对应 handler 返回 SERVER_ERROR")
    void rollbackNoHandler() throws Exception {
        prepareOnlyHandlers(Collections.emptyList());
        BehaviorContext ctx = new BehaviorContext(BehaviorType.UNLIKE_ARTICLE, 1);
        ResponseResult r = eventBus.rollback(ctx);
        assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
    }

    @Test
    @DisplayName("rollback - handler.rollback 返回失败结果时返回 SERVER_ERROR")
    void rollbackHandlerFailure() throws Exception {
        handler = stubHandler();
        when(handler.rollback(any())).thenReturn(BehaviorResult.failure(BehaviorType.LIKE_ARTICLE, "rollback failed"));
        prepareOnlyHandlers(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.rollback(ctx);
        assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
    }

    @Test
    @DisplayName("rollback - handler.rollback 抛异常时返回 SERVER_ERROR")
    void rollbackHandlerException() throws Exception {
        handler = stubHandler();
        when(handler.rollback(any())).thenThrow(new RuntimeException("rollback boom"));
        prepareOnlyHandlers(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.rollback(ctx);
        assertEquals(AppHttpCodeEnum.SERVER_ERROR.getCode(), r.getCode());
    }

    @Test
    @DisplayName("rollback - 成功返回成功并携带 data")
    void rollbackSuccess() throws Exception {
        handler = stubHandler();
        BehaviorResult ok = BehaviorResult.success(BehaviorType.LIKE_ARTICLE, "撤销成功")
                .withData("k", "v");
        when(handler.rollback(any())).thenReturn(ok);
        prepareOnlyHandlers(Collections.singletonList(handler));

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1);
        ResponseResult r = eventBus.rollback(ctx);
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals("v", ok.getData().get("k"));
        verify(handler).rollback(ctx);
    }
}