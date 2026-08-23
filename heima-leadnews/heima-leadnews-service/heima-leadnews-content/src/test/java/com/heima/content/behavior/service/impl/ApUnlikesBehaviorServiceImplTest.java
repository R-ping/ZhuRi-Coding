package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.pins.ApUserActionLogMapper;
import com.heima.model.behavior.dtos.UnLikesBehaviorDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.ApUserActionLog;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ApUnlikesBehaviorServiceImpl 单元测试（不喜欢/取消不喜欢文章行为）
 *
 * <p>覆盖公开方法 unLike() 全部分支：
 * <ul>
 *   <li>articleId 为 null → PARAM_INVALID；</li>
 *   <li>未登录 → NEED_LOGIN；</li>
 *   <li>成功分支：写入行为日志，type=0 记 UNLIKE_ARTICLE、type=1 记 CANCEL_UNLIKE_ARTICLE。</li>
 * </ul>
 * 依赖 apUserActionLogMapper 用 @Mock，ServiceImpl 用 @InjectMocks 注入。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApUnlikesBehaviorServiceImplTest {

    @Mock
    private ApUserActionLogMapper apUserActionLogMapper;

    @InjectMocks
    private ApUnlikesBehaviorServiceImpl service;

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ==================== 辅助 ====================

    private UnLikesBehaviorDto dto(Long articleId, Short type) {
        UnLikesBehaviorDto d = new UnLikesBehaviorDto();
        d.setArticleId(articleId);
        d.setType(type);
        return d;
    }

    private void loggedIn(Integer id) {
        ApUser user = new ApUser();
        user.setId(id);
        AppThreadLocalUtil.setUser(user);
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("unLike - articleId 为 null 返回 PARAM_INVALID")
    void unLikeNullArticleId() {
        ResponseResult r = service.unLike(dto(null, (short) 0));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        verify(apUserActionLogMapper, never()).insert(any(ApUserActionLog.class));
    }

    // ==================== 登录校验 ====================

    @Test
    @DisplayName("unLike - 未登录返回 NEED_LOGIN")
    void unLikeNotLogin() {
        ResponseResult r = service.unLike(dto(1L, (short) 0));
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
        verify(apUserActionLogMapper, never()).insert(any(ApUserActionLog.class));
    }

    // ==================== 成功分支 ====================

    @Test
    @DisplayName("unLike - 不喜欢成功(type=0)：日志 actionType=UNLIKE_ARTICLE")
    void unLikeSuccessType0() {
        loggedIn(100);
        ResponseResult r = service.unLike(dto(1L, (short) 0));
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());

        ArgumentCaptor<ApUserActionLog> captor = ArgumentCaptor.forClass(ApUserActionLog.class);
        verify(apUserActionLogMapper).insert(captor.capture());
        ApUserActionLog log = captor.getValue();
        assertEquals("UNLIKE_ARTICLE", log.getActionType());
        assertTrue(log.getActionDetail().contains("文章ID:1"));
        assertEquals(100L, log.getUserId());
    }

    @Test
    @DisplayName("unLike - 取消不喜欢成功(type=1)：日志 actionType=CANCEL_UNLIKE_ARTICLE")
    void unLikeSuccessType1() {
        loggedIn(100);
        ResponseResult r = service.unLike(dto(1L, (short) 1));
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());

        ArgumentCaptor<ApUserActionLog> captor = ArgumentCaptor.forClass(ApUserActionLog.class);
        verify(apUserActionLogMapper).insert(captor.capture());
        assertEquals("CANCEL_UNLIKE_ARTICLE", captor.getValue().getActionType());
    }
}