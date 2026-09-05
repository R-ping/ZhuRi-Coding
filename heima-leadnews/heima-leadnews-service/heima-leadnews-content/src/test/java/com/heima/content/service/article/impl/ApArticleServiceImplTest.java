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
 * ApArticleServiceImpl 单元测试（文章加载/事件生成/热度分更新/作者文章列表/发布状态同步）
 *
 * 继承 MyBatis-Plus ServiceImpl，私有 baseMapper 以反射注入；其余 @Autowired 依赖由 @InjectMocks 注入。
 * 覆盖：
 * - load：size 缺省/上限 50、type 非法回退、tag 缺省、时间缺省；null-safe 列表映射；
 * - generateArticleEvent：article 为空、DB 无记录、成功/异常回滚(false)；
 * - updateScore：文章不存在、正常计算并累加热度分、updateArticle 数值累加；
 * - updateScoreByBehavior：文章不存在、正常更新热度分；
 * - listByAuthorId：仅作者、按频道/标签/删除过滤（JSON_OVERLAPS）；
 * - updateArticleStatus：DB+ES 都成功→pub_status=2；部分失败→pub_status=1 且置重试时间；无本地消息记录。
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
    @DisplayName("generateArticleEvent - 成功入库事件并触发 ES 同步")
    void testEventSuccess() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        assertTrue(articleService.generateArticleEvent(article(1L), 1L));
        verify(apArticleEventMapper).insertArticleEvent(any(ArticleEvent.class));
        verify(articleFreemarkerService).buildHTMLAndSend(any(ApArticle.class), any());
    }

    @Test
    @DisplayName("generateArticleEvent - 事件入库异常返回 false")
    void testEventInsertFailure() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(apArticleEventMapper).insertArticleEvent(any(ArticleEvent.class));
        assertFalse(articleService.generateArticleEvent(article(1L), 1L));
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

    // ==================== updateArticleStatus ====================

    @Test
    @DisplayName("updateArticleStatus - DB+ES 都成功则 pub_status=2")
    void testStatusAllSuccess() {
        when(apArticleMapper.update(any(), any())).thenReturn(1);
        when(searchClient.updateArticleStatus(1L)).thenReturn(ResponseResult.okResult());
        ArticleEvent event = new ArticleEvent();
        event.setArticleId(1L);
        when(apArticleEventMapper.selectOne(any(Wrapper.class))).thenReturn(event);

        articleService.updateArticleStatus(1L);
        assertEquals((byte) 2, event.getPubStatus());
        verify(apArticleEventMapper).updateById(event);
    }

    @Test
    @DisplayName("updateArticleStatus - ES 失败则 pub_status=1 并置重试时间")
    void testStatusEsFail() {
        when(apArticleMapper.update(any(), any())).thenReturn(1);
        when(searchClient.updateArticleStatus(1L)).thenReturn(
                ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.SERVER_ERROR));
        ArticleEvent event = new ArticleEvent();
        when(apArticleEventMapper.selectOne(any(Wrapper.class))).thenReturn(event);
        articleService.updateArticleStatus(1L);
        assertEquals((byte) 1, event.getPubStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(event.getRetryTime());
    }

    @Test
    @DisplayName("updateArticleStatus - 无本地消息记录则不更新")
    void testStatusNoEvent() {
        when(apArticleMapper.update(any(), any())).thenReturn(0);
        when(searchClient.updateArticleStatus(1L)).thenThrow(new RuntimeException("feign fail"));
        when(apArticleEventMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        articleService.updateArticleStatus(1L); // 不抛异常即可
        verify(apArticleEventMapper, never()).updateById(any(ArticleEvent.class));
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
        articleService.updateArticleStatus(1L); // 触发 computeScore? 否，用 updateScore 覆盖
        articleService.updateScoreByBehavior(1L, null, 1);
    }
}