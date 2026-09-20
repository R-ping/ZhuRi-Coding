package com.zhuri.coding.content.behavior.controller.v1;

import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BehaviorController 单元测试（统一用户行为入口，8 个端点；关注已收敛到 /api/v1/follow/do）
 *
 * 覆盖：
 * - 未登录：like/unlike/collect/uncollect/comment 返回 NEED_LOGIN；
 * - browse 未登录放行返回 okResult(200)；
 * - 参数缺失（targetUserId/targetType/targetId 为 null）返回 PARAM_INVALID；
 * - 参数齐全时通过 ArgumentCaptor 断言 BehaviorContext 的
 *   getUserId/getTargetType/getTargetId/getTargetUserId 并调用 execute/rollback；
 * - browse 的 targetType：1=文章、2=沸点、4=课程，其它类型返回 PARAM_INVALID。
 */
@ExtendWith(MockitoExtension.class)
class BehaviorControllerTest {

    @Mock
    private BehaviorEventBus behaviorEventBus;

    private BehaviorController controller = new BehaviorController();

    /** 反射注入 mock 的事件总线 */
    private void injectEventBus() throws Exception {
        Field f = BehaviorController.class.getDeclaredField("behaviorEventBus");
        f.setAccessible(true);
        f.set(controller, behaviorEventBus);
    }

    private ApUser loggedUser() {
        ApUser user = new ApUser();
        user.setId(1);
        user.setNickname("张三");
        user.setImage("avatar.png");
        return user;
    }

    private Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void stubExecuteOk() {
        lenient().when(behaviorEventBus.execute(any())).thenReturn(ResponseResult.okResult());
    }

    private void stubRollbackOk() {
        lenient().when(behaviorEventBus.rollback(any())).thenReturn(ResponseResult.okResult());
    }

    // ==================== 未登录 ====================

    @Test
    @DisplayName("未登录 - like/unlike/collect/uncollect/comment 均返回 NEED_LOGIN")
    void testAllEndpointsNeedLogin() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.clear();

        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.like(params("targetType", 1, "targetId", 456)).getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.unlike(params("targetType", 1, "targetId", 456)).getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.collect(params("targetType", 1, "targetId", 456)).getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.uncollect(params("targetId", 456)).getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.comment(params("targetType", 1, "targetId", 456)).getCode());

        verify(behaviorEventBus, never()).execute(any());
        verify(behaviorEventBus, never()).rollback(any());
    }

    @Test
    @DisplayName("未登录 - browse 放行，返回 okResult(200)")
    void testBrowseNeedLoginReturnsOk() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.clear();

        ResponseResult result = controller.browse(params("targetType", 1, "targetId", 456));
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
        verify(behaviorEventBus, never()).execute(any());
    }

    // ==================== 参数缺失 ====================

    @Test
    @DisplayName("参数缺失 - like/unlike/collect 缺 targetType 或 targetId 返回 PARAM_INVALID")
    void testLikeUnlikeCollectParamInvalid() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.like(new HashMap<>()).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.unlike(new HashMap<>()).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.collect(params("targetId", 456)).getCode()); // 缺 targetType
        verify(behaviorEventBus, never()).execute(any());
        verify(behaviorEventBus, never()).rollback(any());
    }

    @Test
    @DisplayName("参数缺失 - uncollect 缺 targetId 返回 PARAM_INVALID")
    void testUncollectParamInvalid() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.uncollect(new HashMap<>()).getCode());
        verify(behaviorEventBus, never()).rollback(any());
    }

    @Test
    @DisplayName("参数缺失 - comment 缺 targetType/targetId 返回 PARAM_INVALID")
    void testCommentParamInvalid() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.comment(new HashMap<>()).getCode());
        verify(behaviorEventBus, never()).execute(any());
    }

    @Test
    @DisplayName("参数缺失 - browse 缺 targetType/targetId 返回 PARAM_INVALID")
    void testBrowseParamInvalid() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.browse(new HashMap<>()).getCode());
        verify(behaviorEventBus, never()).execute(any());
    }

    // ==================== 点赞 / 取消点赞 ====================

    @Test
    @DisplayName("like targetType=1 - LIKE_ARTICLE 上下文并调用 execute")
    void testLikeArticle() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.like(params("targetType", 1, "targetId", 456, "targetUserId", 789));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        BehaviorContext ctx = captor.getValue();
        assertEquals(BehaviorType.LIKE_ARTICLE, ctx.getBehaviorType());
        assertEquals(1, ctx.getUserId());
        assertEquals(1, ctx.getTargetType());
        assertEquals(456L, ctx.getTargetId());
        assertEquals(789, ctx.getTargetUserId());
    }

    @Test
    @DisplayName("like targetType=2 - LIKE_PIN 上下文")
    void testLikePin() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.like(params("targetType", 2, "targetId", 456, "targetUserId", 789));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        assertEquals(BehaviorType.LIKE_PIN, captor.getValue().getBehaviorType());
    }

    @Test
    @DisplayName("unlike - 构造 UNLIKE 上下文并调用 rollback")
    void testUnlike() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubRollbackOk();

        controller.unlike(params("targetType", 1, "targetId", 456));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).rollback(captor.capture());
        BehaviorContext ctx = captor.getValue();
        assertEquals(BehaviorType.UNLIKE_ARTICLE, ctx.getBehaviorType());
        assertEquals(1, ctx.getTargetType());
        assertEquals(456L, ctx.getTargetId());
        assertNull(ctx.getTargetUserId()); // unlike 未设置目标用户
    }

    // ==================== 收藏 / 取消收藏 ====================

    @Test
    @DisplayName("collect - 构造 COLLECT_ARTICLE 上下文并调用 execute（含用户信息）")
    void testCollect() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.collect(params("targetType", 1, "targetId", 456, "targetUserId", 789));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        BehaviorContext ctx = captor.getValue();
        assertEquals(BehaviorType.COLLECT_ARTICLE, ctx.getBehaviorType());
        assertEquals(1, ctx.getTargetType());
        assertEquals(456L, ctx.getTargetId());
        assertEquals(789, ctx.getTargetUserId());
        assertEquals("张三", ctx.getUserName());
    }

    @Test
    @DisplayName("uncollect - 构造 UNCOLLECT_ARTICLE 上下文并调用 rollback（targetType 固定为 1）")
    void testUncollect() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubRollbackOk();

        controller.uncollect(params("targetId", 456));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).rollback(captor.capture());
        BehaviorContext ctx = captor.getValue();
        assertEquals(BehaviorType.UNCOLLECT_ARTICLE, ctx.getBehaviorType());
        assertEquals(1, ctx.getTargetType());
        assertEquals(456L, ctx.getTargetId());
    }

    // ==================== 评论 ====================

    @Test
    @DisplayName("comment - 构造 COMMENT 上下文（含 commentId/commentContent）并调用 execute")
    void testComment() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.comment(params("targetType", 1, "targetId", 456,
            "targetUserId", 789, "commentId", 99L, "commentContent", "不错"));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        BehaviorContext ctx = captor.getValue();
        assertEquals(BehaviorType.COMMENT_ARTICLE, ctx.getBehaviorType());
        assertEquals(1, ctx.getTargetType());
        assertEquals(456L, ctx.getTargetId());
        assertEquals(789, ctx.getTargetUserId());
        assertEquals(99L, ctx.getExtraLong("commentId"));
        assertEquals("不错", ctx.getExtraString("commentContent"));
    }

    @Test
    @DisplayName("comment targetType=2 - COMMENT_PIN 上下文")
    void testCommentPin() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.comment(params("targetType", 2, "targetId", 456, "targetUserId", 789));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        assertEquals(BehaviorType.COMMENT_PIN, captor.getValue().getBehaviorType());
    }

    // ==================== 浏览 ====================

    @Test
    @DisplayName("browse targetType=1 - BROWSE_ARTICLE")
    void testBrowseArticle() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.browse(params("targetType", 1, "targetId", 456, "targetUserId", 789));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        BehaviorContext ctx = captor.getValue();
        assertEquals(BehaviorType.BROWSE_ARTICLE, ctx.getBehaviorType());
        assertEquals(1, ctx.getTargetType());
        assertEquals(456L, ctx.getTargetId());
        assertEquals(789, ctx.getTargetUserId());
        assertNull(ctx.getUserName()); // 浏览行为不携带用户信息
    }

    @Test
    @DisplayName("browse targetType=2 - BROWSE_PIN")
    void testBrowsePin() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.browse(params("targetType", 2, "targetId", 456));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        assertEquals(BehaviorType.BROWSE_PIN, captor.getValue().getBehaviorType());
    }

    @Test
    @DisplayName("browse targetType=4 - BROWSE_COURSE")
    void testBrowseCourse() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());
        stubExecuteOk();

        controller.browse(params("targetType", 4, "targetId", 456));

        ArgumentCaptor<BehaviorContext> captor = ArgumentCaptor.forClass(BehaviorContext.class);
        verify(behaviorEventBus).execute(captor.capture());
        assertEquals(BehaviorType.BROWSE_COURSE, captor.getValue().getBehaviorType());
    }

    @Test
    @DisplayName("browse 不支持的 targetType=5 返回 PARAM_INVALID")
    void testBrowseUnsupportedType() throws Exception {
        injectEventBus();
        AppThreadLocalUtil.setUser(loggedUser());

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            controller.browse(params("targetType", 5, "targetId", 456)).getCode());
        verify(behaviorEventBus, never()).execute(any());
    }
}