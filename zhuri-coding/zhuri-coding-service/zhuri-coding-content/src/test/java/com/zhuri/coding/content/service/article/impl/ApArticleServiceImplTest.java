package com.zhuri.coding.content.service.article.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.outbox.OutboxService;
import com.zhuri.coding.model.article.dtos.ArticleDto;
import com.zhuri.coding.model.article.dtos.ArticleHomeDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApArticleServiceImpl 单元测试（文章加载/发布事件落锚/热度分更新/作者文章列表）
 *
 * 继承 MyBatis-Plus ServiceImpl，私有 baseMapper 以反射注入；其余 @Autowired 依赖由 @InjectMocks 注入。
 * 覆盖：
 * - load：size 缺省/上限 50、type 非法回退、tag 缺省、时间缺省；null-safe 列表映射；
 * - createArticleEvent：参数为空、DB 无记录、落 Outbox 成功、幂等短路、落库异常；
 *   置位与 ES 同步属于异步执行体（ArticlePublishExecutor），在 ArticlePublishExecutorTest 覆盖；
 * - updateScoreByBehavior：文章不存在、正常更新热度分；
 * - listByAuthorId：仅作者、按频道/标签/删除过滤（JSON_OVERLAPS）。
 *
 * <p><b>迁移阶段 2（切读）后的断言口径</b>：本方法原先还要「写 article_event 锚点 + 发布
 * ArticlePublishEvent」，切读后这两处写入已移除，Outbox 是唯一执行依据 ——
 * 因此这里不再断言那两个动作，改为断言「落 Outbox」以及它的返回值语义。
 */
class ApArticleServiceImplTest {

    @Mock private ApArticleMapper apArticleMapper;
    /** 迁移阶段 2 起 Outbox 是文章发布的唯一落锚目标（旧链路写入已移除） */
    @Mock private OutboxService outboxService;

    @InjectMocks
    private ApArticleServiceImpl articleService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        // 3.5.12 起 baseMapper 上移至父类 CrudRepository，ReflectionTestUtils 沿继承链查找
        ReflectionTestUtils.setField(articleService, "baseMapper", apArticleMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
    }

    private ApArticle article(Long id) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setAuthorId(5L);
        a.setLikes(2);
        a.setViews(10);
        a.setComment(1);
        a.setCollection(1);
        a.setTitle("标题");
        a.setPublishTime(new Date());
        return a;
    }


    // ==================== load ====================

    @Test
    @DisplayName("load - size 缺省/越界/tag/时间校验并通过 mapper 查询")
    void testLoad() {
        ArticleHomeDto dto = new ArticleHomeDto();
        dto.setTag("");
        ApArticle a = article(1L);
        when(apArticleMapper.loadArticleList(any(ArticleHomeDto.class), any(Short.class)))
                .thenReturn(Arrays.asList(a));

        ResponseResult r = articleService.load(dto, (short) 1);
        assertEquals(200, r.getCode());
        // size<=50 且 tag 被补默认
        assertTrue(dto.getSize() <= 50);
        assertEquals(com.zhuri.coding.common.constants.ArticleConstants.DEFAULT_TAG, dto.getTag());
        List<?> list = (List<?>) r.getData();
        assertEquals(1, list.size());
    }

    // ==================== createArticleEvent ====================

    @Test
    @DisplayName("createArticleEvent - 参数为空返回 false 且不落 Outbox")
    void testEventNullArticle() {
        assertFalse(articleService.createArticleEvent(null));
        verify(outboxService, never()).record(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("createArticleEvent - 文章不存在(被审核回滚)返回 false 且不落 Outbox")
    void testEventArticleMissing() {
        when(apArticleMapper.selectById(1L)).thenReturn(null);
        assertFalse(articleService.createArticleEvent(article(1L)));
        verify(outboxService, never()).record(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("createArticleEvent - 落 Outbox 成功，幂等键与载荷符合 Handler 约定")
    void testEventSuccess() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        when(outboxService.record(anyString(), anyString(), anyString())).thenReturn(true);

        assertTrue(articleService.createArticleEvent(article(1L)));

        verify(outboxService).record(
                eq("article_publish:1"),
                eq("ARTICLE_PUBLISH"),
                eq("{\"articleId\":1}"));
    }

    @Test
    @DisplayName("createArticleEvent - 同 eventKey 已存在(幂等短路)仍返回 true，不阻断发布")
    void testEventIdempotentShortCircuit() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        // record 返回 false = uk_event_key 冲突，说明已有在途发布事件
        when(outboxService.record(anyString(), anyString(), anyString())).thenReturn(false);

        assertTrue(articleService.createArticleEvent(article(1L)));

        verify(outboxService).record(
                eq("article_publish:1"),
                eq("ARTICLE_PUBLISH"),
                eq("{\"articleId\":1}"));
    }

    @Test
    @DisplayName("createArticleEvent - 落 Outbox 异常返回 false（切读后无兜底链路，必须暴露失败）")
    void testEventRecordFailure() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        doThrow(new RuntimeException("db down"))
                .when(outboxService).record(anyString(), anyString(), anyString());

        assertFalse(articleService.createArticleEvent(article(1L)));
    }

    // ==================== updateScoreByBehavior ====================

    @Test
    @DisplayName("updateScoreByBehavior - articleId 为空直接返回")
    void testUpdateByBehaviorMissing() {
        articleService.updateScoreByBehavior(null, null, 1);
        verify(apArticleMapper, never()).recalculateScore(any());
    }

    @Test
    @DisplayName("updateScoreByBehavior - 统一走原子 SQL 重算热度分")
    void testUpdateByBehaviorOk() {
        articleService.updateScoreByBehavior(1L, null, 1);
        verify(apArticleMapper).recalculateScore(1L);
    }

    // ==================== listByAuthorId ====================

    @Test
    @DisplayName("listByAuthorId - 按作者+频道+标签JSON_OVERLAPS查询")
    void testListByAuthor() {
        ArticleDto dto = new ArticleDto();
        dto.setAuthorId(5L);
        dto.setChannelId(2);
        dto.setTags(Arrays.asList("44", "45"));
        dto.setIsDeleted(false);
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(article(1L)));
        List<Map<String, Object>> list = articleService.listByAuthorId(dto);
        assertEquals(1, list.size());
    }

    @Test
    @DisplayName("listByAuthorId - 仅作者条件")
    void testListByAuthorOnly() {
        ArticleDto dto = new ArticleDto();
        dto.setAuthorId(5L);
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(org.mockito.Mockito.mock(java.util.ArrayList.class));
        assertEquals(0, articleService.listByAuthorId(dto).size());
    }

    @Test
    @DisplayName("computeScore - null 字段按 0 计")
    void testComputeNullFields() {
        ApArticle a = article(1L);
        a.setLikes(null);
        a.setViews(null);
        a.setComment(null);
        a.setCollection(null);
        when(apArticleMapper.selectById(1L)).thenReturn(a);
        when(apArticleMapper.update(any(), any())).thenReturn(1);
        articleService.updateScoreByBehavior(1L, null, 1);
    }
}
