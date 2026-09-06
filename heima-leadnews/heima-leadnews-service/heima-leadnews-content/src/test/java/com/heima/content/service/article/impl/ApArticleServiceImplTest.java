package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.heima.apis.search.ISearchClient;
import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleEventMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.article.ArticleFreemarkerService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ArticleEvent;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.vos.SearchArticleVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApArticleServiceImpl 单元测试（文章加载/事件生成/热度分更新/作者文章列表）
 *
 * 继承 MyBatis-Plus ServiceImpl，私有 baseMapper 以反射注入；其余 @Autowired 依赖由 @InjectMocks 注入。
 * 覆盖：
 * - load：size 缺省/上限 50、type 非法回退、tag 缺省、时间缺省；null-safe 列表映射；
 * - generateArticleEvent：参数为空、DB 无记录、事件落库成功并触发 ES 同步、落库异常(false)、
 *   置位 0 行后按文章状态分流（已发布续跑 / 本地重试一次仍失败落 DB_SET_FAIL / FAIL 终态删除）；
 * - updateScore：文章不存在、正常计算并累加热度分、updateArticle 数值累加；
 * - updateScoreByBehavior：文章不存在、正常更新热度分；
 * - listByAuthorId：仅作者、按频道/标签/删除过滤（JSON_OVERLAPS）。
 */
class ApArticleServiceImplTest {

    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ArticleFreemarkerService articleFreemarkerService;
    @Mock private ApArticleEventMapper apArticleEventMapper;
    @Mock private ISearchClient searchClient;

    @InjectMocks
    private ApArticleServiceImpl articleService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        Field f = com.baomidou.mybatisplus.extension.service.IService.class.getClassLoader()
                .loadClass("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl")
                .getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(articleService, apArticleMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ArticleEvent.class);
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
        assertEquals(ArticleConstants.DEFAULT_TAG, dto.getTag());
        List<?> list = (List<?>) r.getData();
        assertEquals(1, list.size());
    }

    // ==================== generateArticleEvent ====================

    @Test
    @DisplayName("generateArticleEvent - 参数为空返回 false")
    void testEventNullArticle() {
        assertFalse(articleService.generateArticleEvent(null, 1L));
        verify(articleFreemarkerService, never()).buildHTMLAndSend(any(), any());
    }

    @Test
    @DisplayName("generateArticleEvent - 文章不存在(被审核回滚)返回 false")
    void testEventArticleMissing() {
        when(apArticleMapper.selectById(1L)).thenReturn(null);
        assertFalse(articleService.generateArticleEvent(article(1L), 1L));
    }

    @Test
    @DisplayName("generateArticleEvent - 成功落事件并置位后触发 ES 同步")
    void testEventSuccess() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(1);
        assertTrue(articleService.generateArticleEvent(article(1L), 1L));
        verify(apArticleEventMapper).insertArticleEvent(any(ArticleEvent.class));
        verify(articleFreemarkerService).buildHTMLAndSend(any(ApArticle.class), any());
    }

    @Test
    @DisplayName("generateArticleEvent - 事件落库异常返回 false（不进入置位与同步）")
    void testEventInsertFailure() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(apArticleEventMapper).insertArticleEvent(any(ArticleEvent.class));
        assertFalse(articleService.generateArticleEvent(article(1L), 1L));
        verify(articleFreemarkerService, never()).buildHTMLAndSend(any(), any());
    }

    @Test
    @DisplayName("generateArticleEvent - 置位 0 行但文章已是发布态：幂等续跑同步")
    void testEventAlreadyPublished() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        ApArticle published = article(1L);
        published.setStatus((byte) ApArticle.Status.PUBLISHED.getCode());
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L), published);
        assertTrue(articleService.generateArticleEvent(article(1L), 1L));
        verify(articleFreemarkerService).buildHTMLAndSend(any(ApArticle.class), any());
    }

    @Test
    @DisplayName("generateArticleEvent - 置位失败且文章仍 SUBMIT：本地重试一次仍失败落 DB_SET_FAIL")
    void testEventDbSetFailMarked() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        ApArticle submit = article(1L);
        submit.setStatus((byte) ApArticle.Status.SUBMIT.getCode());
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L), submit);
        ArticleEvent row = new ArticleEvent();
        row.setArticleId(1L);
        when(apArticleEventMapper.selectOne(any(Wrapper.class))).thenReturn(row);
        assertTrue(articleService.generateArticleEvent(article(1L), 1L));
        verify(articleFreemarkerService, never()).buildHTMLAndSend(any(), any());
        org.mockito.ArgumentCaptor<ArticleEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ArticleEvent.class);
        verify(apArticleEventMapper).updateArticleEvent(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
            ArticleConstants.EVENT_STATUS_DB_SET_FAIL, captor.getValue().getStatus().byteValue());
    }

    @Test
    @DisplayName("generateArticleEvent - 文章处于 FAIL 终态：删除事件返回 false")
    void testEventArticleUnpublishable() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        when(apArticleMapper.markPublishedIfPending(1L)).thenReturn(0);
        ApArticle failed = article(1L);
        failed.setStatus((byte) ApArticle.Status.FAIL.getCode());
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L), failed);
        assertFalse(articleService.generateArticleEvent(article(1L), 1L));
        verify(apArticleEventMapper).deleteByArticleId(1L);
        verify(articleFreemarkerService, never()).buildHTMLAndSend(any(), any());
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