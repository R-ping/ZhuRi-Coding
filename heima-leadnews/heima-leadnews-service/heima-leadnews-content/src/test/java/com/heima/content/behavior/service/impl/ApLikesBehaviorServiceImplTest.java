package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.pins.ApUserActionLogMapper;
import com.heima.content.service.article.ApArticleService;
import com.heima.model.behavior.dtos.LikesBehaviorDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.mess.UpdateArticleMess;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.ApUserActionLog;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * ApLikesBehaviorServiceImpl 单元测试（点赞/取消点赞行为）
 *
 * <p>覆盖公开方法 like() 全部分支：
 * <ul>
 *   <li>参数校验：dto 为 null、articleId 为 null、type/operation 越界 → PARAM_INVALID；</li>
 *   <li>登录校验：未登录 → NEED_LOGIN；</li>
 *   <li>成功分支：点赞(operation=0) 与取消点赞(operation=1) 均走 mapper.update + actionLog.insert + updateScoreByBehavior。</li>
 * </ul>
 * 三个依赖（mapper/service）全部用 @Mock，ServiceImpl 用 @InjectMocks 注入。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApLikesBehaviorServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApUserActionLogMapper apUserActionLogMapper;
    @Mock
    private ApArticleService apArticleService;

    @InjectMocks
    private ApLikesBehaviorServiceImpl service;

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ==================== 辅助 ====================

    private LikesBehaviorDto dto(Long articleId, Short type, Short operation) {
        LikesBehaviorDto d = new LikesBehaviorDto();
        d.setArticleId(articleId);
        d.setType(type);
        d.setOperation(operation);
        return d;
    }

    private void loggedIn(Integer id) {
        ApUser user = new ApUser();
        user.setId(id);
        AppThreadLocalUtil.setUser(user);
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("like - dto 为 null 返回 PARAM_INVALID")
    void likeNullDto() {
        ResponseResult r = service.like(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("like - articleId 为 null 返回 PARAM_INVALID")
    void likeNullArticleId() {
        ResponseResult r = service.like(dto(null, (short) 0, (short) 0));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("like - type 越界（>2）返回 PARAM_INVALID")
    void likeInvalidType() {
        ResponseResult r = service.like(dto(1L, (short) 5, (short) 0));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("like - operation 越界（<0）返回 PARAM_INVALID")
    void likeInvalidOperation() {
        ResponseResult r = service.like(dto(1L, (short) 0, (short) -1));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    // ==================== 登录校验 ====================

    @Test
    @DisplayName("like - 未登录返回 NEED_LOGIN")
    void likeNotLogin() {
        ResponseResult r = service.like(dto(1L, (short) 0, (short) 0));
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
    }

    // ==================== 成功分支 ====================

    @Test
    @DisplayName("like - 点赞成功(operation=0)：更新点赞数+写日志+更新热度+1")
    void likeSuccess() {
        loggedIn(100);
        ResponseResult r = service.like(dto(1L, (short) 0, (short) 0));
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(apArticleMapper).update(isNull(), any());
        verify(apUserActionLogMapper).insert(any(ApUserActionLog.class));
        verify(apArticleService).updateScoreByBehavior(eq(1L), eq(UpdateArticleMess.UpdateArticleType.LIKES), eq(1));
    }

    @Test
    @DisplayName("like - 取消点赞成功(operation=1)：更新热度-1")
    void likeSuccessCancel() {
        loggedIn(100);
        ResponseResult r = service.like(dto(1L, (short) 0, (short) 1));
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        verify(apArticleService).updateScoreByBehavior(eq(1L), eq(UpdateArticleMess.UpdateArticleType.LIKES), eq(-1));
    }
}