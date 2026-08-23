package com.heima.content.behavior.service.impl;

import com.heima.content.behavior.service.BehaviorEventBus;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApBrowseHistoryMapper;
import com.heima.content.service.article.ApArticleService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApBrowseHistory;
import com.heima.model.behavior.dtos.ReadBehaviorDto;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.mess.UpdateArticleMess;
import com.heima.model.user.pojos.ApUser;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApReadBehaviorServiceImpl 单元测试（文章浏览行为）
 *
 * <p>覆盖公开方法 readBehavior() 全部分支：
 * <ul>
 *   <li>参数校验：dto 为 null、articleId 为 null → PARAM_INVALID；</li>
 *   <li>未登录静默成功：返回 counted=false；</li>
 *   <li>首次浏览(无历史)：插入浏览历史+累加阅读数+更新热度，counted=true；</li>
 *   <li>重复浏览(有历史)：不重复计数，counted=false；</li>
 *   <li>等级体系接入：article 存在时调用 behaviorEventBus.execute（异常被吞掉不影响主流程）。</li>
 * </ul>
 * ApArticleMapper 用 Mock，其中 BaseMapper.selectOne/update 均以单参/双参形式存根。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApReadBehaviorServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApBrowseHistoryMapper apBrowseHistoryMapper;
    @Mock
    private ApArticleService apArticleService;
    @Mock
    private BehaviorEventBus behaviorEventBus;

    @InjectMocks
    private ApReadBehaviorServiceImpl service;

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ==================== 辅助 ====================

    private ReadBehaviorDto dto(Long articleId, Short count) {
        ReadBehaviorDto d = new ReadBehaviorDto();
        d.setArticleId(articleId);
        d.setCount(count);
        return d;
    }

    private void loggedIn(Integer id) {
        ApUser user = new ApUser();
        user.setId(id);
        user.setNickname("nick");
        user.setImage("img");
        AppThreadLocalUtil.setUser(user);
    }

    private ApArticle article(Long id, Long authorId) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setAuthorId(authorId);
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseResult r) {
        return (Map<String, Object>) r.getData();
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("readBehavior - dto 为 null 返回 PARAM_INVALID")
    void readNullDto() {
        ResponseResult r = service.readBehavior(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("readBehavior - articleId 为 null 返回 PARAM_INVALID")
    void readNullArticleId() {
        ResponseResult r = service.readBehavior(dto(null, (short) 1));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
    }

    // ==================== 未登录静默成功 ====================

    @Test
    @DisplayName("readBehavior - 未登录不计入阅读数（静默返回 counted=false）")
    void readNotLogin() {
        ResponseResult r = service.readBehavior(dto(1L, (short) 1));
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(false, dataOf(r).get("counted"));
        verify(apBrowseHistoryMapper, never()).insert(any(ApBrowseHistory.class));
        verify(behaviorEventBus, never()).execute(any());
    }

    // ==================== 首次浏览 ====================

    @Test
    @DisplayName("readBehavior - 首次浏览且文章不存在：计数并跳过等级体系")
    void readFirstTimeArticleNull() {
        loggedIn(10);
        when(apBrowseHistoryMapper.selectCount(any())).thenReturn(0L);
        when(apArticleMapper.selectById(1L)).thenReturn(null);

        ResponseResult r = service.readBehavior(dto(1L, (short) 2));
        assertEquals(true, dataOf(r).get("counted"));
        verify(apBrowseHistoryMapper).insert(any(ApBrowseHistory.class));
        verify(apArticleMapper).update(any(), any());
        verify(apArticleService).updateScoreByBehavior(eq(1L), eq(UpdateArticleMess.UpdateArticleType.VIEWS), eq(2));
        verify(behaviorEventBus, never()).execute(any());
    }

    @Test
    @DisplayName("readBehavior - 首次浏览且文章存在：计数并调用等级体系")
    void readFirstTimeArticleExists() {
        loggedIn(10);
        when(apBrowseHistoryMapper.selectCount(any())).thenReturn(0L);
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, 5L));

        ResponseResult r = service.readBehavior(dto(1L, (short) 1));
        assertEquals(true, dataOf(r).get("counted"));
        verify(apBrowseHistoryMapper).insert(any(ApBrowseHistory.class));
        verify(apArticleMapper).update(any(), any());
        verify(apArticleService).updateScoreByBehavior(eq(1L), eq(UpdateArticleMess.UpdateArticleType.VIEWS), eq(1));
        verify(behaviorEventBus).execute(any());
    }

    // ==================== 重复浏览 ====================

    @Test
    @DisplayName("readBehavior - 重复浏览不重复计数（counted=false），仍接入等级体系")
    void readExistsArticleExists() {
        loggedIn(10);
        when(apBrowseHistoryMapper.selectCount(any())).thenReturn(1L);
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, 5L));

        ResponseResult r = service.readBehavior(dto(1L, (short) 1));
        assertEquals(false, dataOf(r).get("counted"));
        verify(apBrowseHistoryMapper, never()).insert(any(ApBrowseHistory.class));
        verify(apArticleMapper, never()).update(any(), any());
        verify(apArticleService, never()).updateScoreByBehavior(any(), any(), any());
        verify(behaviorEventBus).execute(any());
    }

    @Test
    @DisplayName("readBehavior - 重复浏览且文章不存在：不计数也不接等级体系")
    void readExistsArticleNull() {
        loggedIn(10);
        when(apBrowseHistoryMapper.selectCount(any())).thenReturn(1L);
        when(apArticleMapper.selectById(1L)).thenReturn(null);

        ResponseResult r = service.readBehavior(dto(1L, (short) 1));
        assertEquals(false, dataOf(r).get("counted"));
        verify(behaviorEventBus, never()).execute(any());
    }

    // ==================== 等级体系接入异常被吞 ====================

    @Test
    @DisplayName("readBehavior - behaviorEventBus.execute 抛异常被吞掉，主流程仍成功")
    void readBusExceptionSwallowed() {
        loggedIn(10);
        when(apBrowseHistoryMapper.selectCount(any())).thenReturn(0L);
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, 5L));
        when(behaviorEventBus.execute(any())).thenThrow(new RuntimeException("bus boom"));

        ResponseResult r = service.readBehavior(dto(1L, (short) 1));
        assertEquals(true, dataOf(r).get("counted"));
    }
}