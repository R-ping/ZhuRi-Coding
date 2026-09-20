package com.zhuri.coding.content.service.article.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.column.ApColumnMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.interaction.ApBehaviorLikesMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.mapper.tag.TagMapper;
import com.zhuri.coding.content.service.comment.ApCommentService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.article.vos.ArticleColumnVO;
import com.zhuri.coding.model.article.vos.ArticleDetailVO;
import com.zhuri.coding.model.article.vos.ArticleRecommendVO;
import com.zhuri.coding.model.column.pojos.ApColumn;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.tag.pojos.ApTag;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArticleDetailServiceImplTest {

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private ApArticleContentMapper apArticleContentMapper;

    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Mock
    private ApCollectionMapper apCollectionMapper;

    @Mock
    private ApFollowMapper apFollowMapper;

    @Mock
    private ApColumnMapper apColumnMapper;

    @Mock
    private TagMapper tagMapper;

    @Mock
    private IUserClient userClient;

    @Mock
    private ApCommentService apCommentService;

    @InjectMocks
    private ArticleDetailServiceImpl articleDetailService;

    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    private static final Long TEST_ARTICLE_ID = 2086414899941933058L;
    private static final Long TEST_AUTHOR_ID = 20001L;
    private static final Long TEST_COLUMN_ID = 1001L;
    private static final Integer TEST_USER_ID = 20001;

    private ApArticle testArticle;
    private ApArticleContent testContent;
    private ApArticle prevArticle;
    private ApArticle nextArticle;
    private ApColumn testColumn;
    private ApUser testUser;

    @BeforeEach
    void setUp() {
        // 文章详情评论数来自评论服务，默认按测试文章 comment=3 桩化（未触发的测试用 lenient 避免误报）
        lenient().when(apCommentService.countTopComments(anyLong())).thenReturn(3L);
        // 测试文章
        testArticle = new ApArticle();
        testArticle.setId(TEST_ARTICLE_ID);
        testArticle.setTitle("测试文章标题");
        testArticle.setAuthorId(TEST_AUTHOR_ID);
        testArticle.setAuthorName("测试作者");
        testArticle.setAuthorImage("https://example.com/avatar.png");
        testArticle.setChannelId(1);
        testArticle.setChannelName("前端");
        testArticle.setColumnId(TEST_COLUMN_ID);
        testArticle.setLikes(10);
        testArticle.setCollection(5);
        testArticle.setComment(3);
        testArticle.setViews(100);
        testArticle.setStatus(ApArticle.Status.PUBLISHED.getCode());
        testArticle.setTags(Arrays.asList("Java", "Spring"));
        testArticle.setPublishTime(new Date());
        testArticle.setCreatedTime(new Date());

        // 文章内容
        testContent = new ApArticleContent();
        testContent.setId(1L);
        testContent.setArticleId(TEST_ARTICLE_ID);
        testContent.setContent("# 测试文章\n这是一篇测试文章的正文内容。\n\n## 二级标题\n这里是二级标题下的内容。");

        // 上一篇
        prevArticle = new ApArticle();
        prevArticle.setId(100L);
        prevArticle.setTitle("上一篇测试文章");
        prevArticle.setAuthorId(TEST_AUTHOR_ID);
        prevArticle.setChannelId(1);
        prevArticle.setChannelName("前端");
        prevArticle.setStatus(ApArticle.Status.PUBLISHED.getCode());
        prevArticle.setPublishTime(new Date(System.currentTimeMillis() - 86400000L));
        prevArticle.setLikes(5);
        prevArticle.setCollection(2);
        prevArticle.setComment(1);
        prevArticle.setViews(50);
        prevArticle.setTags(Collections.singletonList("Java"));

        // 下一篇
        nextArticle = new ApArticle();
        nextArticle.setId(200L);
        nextArticle.setTitle("下一篇测试文章");
        nextArticle.setAuthorId(TEST_AUTHOR_ID);
        nextArticle.setChannelId(1);
        nextArticle.setChannelName("前端");
        nextArticle.setStatus(ApArticle.Status.PUBLISHED.getCode());
        nextArticle.setPublishTime(new Date(System.currentTimeMillis() + 86400000L));
        nextArticle.setLikes(15);
        nextArticle.setCollection(8);
        nextArticle.setComment(5);
        nextArticle.setViews(200);
        nextArticle.setTags(Collections.singletonList("Java"));

        // 专栏
        testColumn = new ApColumn();
        testColumn.setId(TEST_COLUMN_ID);
        testColumn.setTitle("测试专栏");
        testColumn.setDescription("这是一个测试专栏的描述");
        testColumn.setCoverImage("https://example.com/column.png");
        testColumn.setSubscribeCount(50);
        testColumn.setArticleCount(10);
        testColumn.setAuthorId(TEST_AUTHOR_ID);

        // 测试用户
        testUser = new ApUser();
        testUser.setId(TEST_USER_ID);
        testUser.setNickname("测试用户");
        testUser.setImage("https://example.com/avatar.png");

        // Mock AppThreadLocalUtil.getUser()
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(testUser);
    }

    @AfterEach
    void tearDown() {
        if (threadLocalMock != null) {
            threadLocalMock.close();
        }
    }

    // ==================== getArticleDetail ====================

    @Test
    @DisplayName("文章详情 - ID为空时返回错误")
    void testGetArticleDetailNullId() {
        ResponseResult result = articleDetailService.getArticleDetail(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("文章详情 - 文章不存在时返回错误")
    void testGetArticleDetailNotFound() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        ResponseResult result = articleDetailService.getArticleDetail(TEST_ARTICLE_ID);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("文章详情 - 成功返回完整文章详情")
    void testGetArticleDetailSuccess() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apArticleContentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testContent);

        // Mock Feign 用户信息
        Map<String, Object> userData = new HashMap<>();
        userData.put("name", "测试作者");
        userData.put("image", "https://example.com/avatar.png");
        ResponseResult feignResult = ResponseResult.okResult(userData);
        when(userClient.getBasicInfo(TEST_AUTHOR_ID)).thenReturn(feignResult);

        // Mock 交互状态 - 未点赞、未收藏、未关注
        when(apBehaviorLikesMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(apCollectionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(apFollowMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        // Mock 标签
        ApTag javaTag = new ApTag();
        javaTag.setId(1);
        javaTag.setName("Java");
        ApTag springTag = new ApTag();
        springTag.setId(2);
        springTag.setName("Spring");
        when(tagMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(javaTag)
                .thenReturn(springTag);

        ResponseResult result = articleDetailService.getArticleDetail(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        ArticleDetailVO vo = (ArticleDetailVO) result.getData();
        assertNotNull(vo);
        assertEquals(TEST_ARTICLE_ID.toString(), vo.getArticleId());
        assertEquals("测试文章标题", vo.getTitle());
        assertEquals(10, vo.getDiggCount());
        assertEquals(5, vo.getCollectCount());
        assertEquals(3, vo.getCommentCount());
        assertEquals(100, vo.getViewCount());
        assertEquals("1", vo.getReadTime());
        assertFalse(vo.getIsDigg());
        assertFalse(vo.getIsCollect());
        assertFalse(vo.getIsFollow());
        assertEquals("测试作者", vo.getAuthorName());
        assertEquals("https://example.com/avatar.png", vo.getAuthorAvatar());

        // 验证标签
        assertNotNull(vo.getTags());
        assertEquals(2, vo.getTags().size());
    }

    @Test
    @DisplayName("文章详情 - 已登录且已点赞、已收藏、已关注时状态正确")
    void testGetArticleDetailWithInteractions() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apArticleContentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testContent);

        // Mock Feign 用户信息
        ResponseResult feignResult = ResponseResult.okResult(new HashMap<>());
        when(userClient.getBasicInfo(TEST_AUTHOR_ID)).thenReturn(feignResult);

        // Mock 交互状态 - 已点赞、已收藏、已关注
        when(apBehaviorLikesMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(apCollectionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(apFollowMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        // Mock 标签
        when(tagMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ResponseResult result = articleDetailService.getArticleDetail(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        ArticleDetailVO vo = (ArticleDetailVO) result.getData();
        assertNotNull(vo);
        assertTrue(vo.getIsDigg());
        assertTrue(vo.getIsCollect());
        assertTrue(vo.getIsFollow());
    }

    @Test
    @DisplayName("文章详情 - 未登录时交互状态全为false")
    void testGetArticleDetailNotLoggedIn() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apArticleContentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testContent);

        // Mock Feign 用户信息
        when(userClient.getBasicInfo(TEST_AUTHOR_ID)).thenReturn(ResponseResult.okResult(new HashMap<>()));

        // Mock 标签
        when(tagMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ResponseResult result = articleDetailService.getArticleDetail(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        ArticleDetailVO vo = (ArticleDetailVO) result.getData();
        assertNotNull(vo);
        assertFalse(vo.getIsDigg());
        assertFalse(vo.getIsCollect());
        assertFalse(vo.getIsFollow());
    }

    // ==================== getArticleColumn ====================

    @Test
    @DisplayName("文章专栏 - ID为空时返回错误")
    void testGetArticleColumnNullId() {
        ResponseResult result = articleDetailService.getArticleColumn(null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("文章专栏 - 文章不存在时返回错误")
    void testGetArticleColumnArticleNotFound() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        ResponseResult result = articleDetailService.getArticleColumn(TEST_ARTICLE_ID);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("文章专栏 - 无专栏时返回空对象")
    void testGetArticleColumnNoColumn() {
        testArticle.setColumnId(null);
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);

        ResponseResult result = articleDetailService.getArticleColumn(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData() instanceof ArticleColumnVO);
    }

    @Test
    @DisplayName("文章专栏 - 成功返回专栏信息（含上下篇导航）")
    void testGetArticleColumnSuccess() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apColumnMapper.selectById(TEST_COLUMN_ID)).thenReturn(testColumn);

        // 上一篇
        when(apArticleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(prevArticle))  // 上一篇
                .thenReturn(Collections.singletonList(nextArticle)); // 下一篇

        // Mock 专栏关注状态（未关注）
        when(apFollowMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ResponseResult result = articleDetailService.getArticleColumn(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        ArticleColumnVO vo = (ArticleColumnVO) result.getData();
        assertNotNull(vo);
        assertEquals(TEST_COLUMN_ID, vo.getColumnId());
        assertEquals("测试专栏", vo.getColumnTitle());
        assertEquals("这是一个测试专栏的描述", vo.getColumnDescription());
        assertEquals(50, vo.getFollowCnt());
        assertEquals(10, vo.getArticleCnt());
        assertFalse(vo.getIsFollow());

        // 验证上下篇导航
        assertEquals("100", vo.getPrevArticleId());
        assertEquals("上一篇测试文章", vo.getPrevArticleTitle());
        assertEquals("200", vo.getNextArticleId());
        assertEquals("下一篇测试文章", vo.getNextArticleTitle());
    }

    // ==================== getRelatedArticles ====================

    @Test
    @DisplayName("相关推荐 - ID为空时返回错误")
    void testGetRelatedArticlesNullId() {
        ResponseResult result = articleDetailService.getRelatedArticles(null, null, 5);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("相关推荐 - 文章不存在时返回错误")
    void testGetRelatedArticlesArticleNotFound() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        ResponseResult result = articleDetailService.getRelatedArticles(TEST_ARTICLE_ID, null, 5);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("相关推荐 - 成功返回同频道文章列表")
    void testGetRelatedArticlesSuccess() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        // 相关推荐为三阶段策略：作者文章(最多3篇) -> 同频道补齐 -> 全局兜底
        // 顺序桩映射：作者文章返回 prevArticle，同频道返回 nextArticle，全局兜底为空
        when(apArticleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(prevArticle))
                .thenReturn(Arrays.asList(nextArticle))
                .thenReturn(Collections.emptyList());

        // Mock 文章内容查询（推荐列表中的文章）
        when(apArticleContentMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(testContent)
                .thenReturn(testContent);

        // Mock 标签
        when(tagMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ResponseResult result = articleDetailService.getRelatedArticles(TEST_ARTICLE_ID, null, 5);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        List<ArticleRecommendVO> list = (List<ArticleRecommendVO>) data.get("list");
        assertEquals(2, list.size());
        assertFalse((Boolean) data.get("has_more"));
    }

    // ==================== getFeaturedArticles ====================

    @Test
    @DisplayName("精选内容 - ID为空时返回错误")
    void testGetFeaturedArticlesNullId() {
        ResponseResult result = articleDetailService.getFeaturedArticles(null, null, 5);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("精选内容 - 文章不存在时返回错误")
    void testGetFeaturedArticlesArticleNotFound() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        ResponseResult result = articleDetailService.getFeaturedArticles(TEST_ARTICLE_ID, null, 5);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("精选内容 - 成功返回同标签文章列表")
    void testGetFeaturedArticlesSuccess() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apArticleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(prevArticle));

        // Mock 文章内容
        when(apArticleContentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testContent);

        // Mock 标签
        when(tagMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ResponseResult result = articleDetailService.getFeaturedArticles(TEST_ARTICLE_ID, null, 5);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        List<ArticleRecommendVO> list = (List<ArticleRecommendVO>) data.get("list");
        assertEquals(1, list.size());
        assertFalse((Boolean) data.get("has_more"));
    }

    // ==================== getRecommendArticles ====================

    @Test
    @DisplayName("为你推荐 - ID为空时返回错误")
    void testGetRecommendArticlesNullId() {
        ResponseResult result = articleDetailService.getRecommendArticles(null, null, 5);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
    }

    @Test
    @DisplayName("为你推荐 - 成功返回推荐文章列表")
    void testGetRecommendArticlesSuccess() {
        when(apArticleMapper.selectRecommendArticles(eq(TEST_ARTICLE_ID), any(), anyInt()))
                .thenReturn(Collections.singletonList(nextArticle));

        // Mock 文章内容
        when(apArticleContentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testContent);

        // Mock 标签
        when(tagMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ResponseResult result = articleDetailService.getRecommendArticles(TEST_ARTICLE_ID, null, 5);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        List<ArticleRecommendVO> list = (List<ArticleRecommendVO>) data.get("list");
        assertEquals(1, list.size());
        assertFalse((Boolean) data.get("has_more"));
    }

    @Test
    @DisplayName("为你推荐 - 无推荐时返回空列表")
    void testGetRecommendArticlesEmpty() {
        when(apArticleMapper.selectRecommendArticles(eq(TEST_ARTICLE_ID), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        ResponseResult result = articleDetailService.getRecommendArticles(TEST_ARTICLE_ID, null, 5);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        List<ArticleRecommendVO> list = (List<ArticleRecommendVO>) data.get("list");
        assertTrue(list.isEmpty());
        assertFalse((Boolean) data.get("has_more"));
    }
}