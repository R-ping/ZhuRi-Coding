package com.zhuri.coding.content.controller.v1.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApBehaviorLikesMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApBehaviorLikes;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.Date;
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
class ArticleInteractionControllerTest {

    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Mock
    private ApCollectionMapper apCollectionMapper;

    @Mock
    private ApArticleMapper apArticleMapper;

    @InjectMocks
    private ArticleInteractionController interactionController;

    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    private static final Long TEST_ARTICLE_ID = 2086414899941933058L;
    private static final Long TEST_AUTHOR_ID = 20002L;
    private static final Integer TEST_USER_ID = 20001;

    private ApUser testUser;
    private ApArticle testArticle;

    @BeforeEach
    void setUp() {
        testUser = new ApUser();
        testUser.setId(TEST_USER_ID);
        testUser.setNickname("测试用户");
        testUser.setImage("https://example.com/avatar.png");

        testArticle = new ApArticle();
        testArticle.setId(TEST_ARTICLE_ID);
        testArticle.setTitle("测试文章标题");
        testArticle.setAuthorId(TEST_AUTHOR_ID);
        testArticle.setLikes(10);
        testArticle.setCollection(5);
        testArticle.setStatus(ApArticle.Status.PUBLISHED.getCode());

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

    // ==================== like ====================

    @Test
    @DisplayName("点赞文章 - 未登录时返回需要登录")
    void testLikeNotLoggedIn() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = interactionController.like(TEST_ARTICLE_ID);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
    }

    @Test
    @DisplayName("点赞文章 - 文章不存在时返回错误")
    void testLikeArticleNotFound() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        ResponseResult result = interactionController.like(TEST_ARTICLE_ID);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("点赞文章 - 成功点赞（未点赞过）")
    void testLikeSuccess() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apBehaviorLikesMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(apBehaviorLikesMapper.insert(any(ApBehaviorLikes.class))).thenReturn(1);
        // 设置更新后的文章（点赞数+1）
        ApArticle updatedArticle = new ApArticle();
        updatedArticle.setLikes(11);
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle, updatedArticle);

        ResponseResult result = interactionController.like(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertTrue((Boolean) data.get("liked"));
        assertEquals(11, data.get("diggCount"));

        verify(apBehaviorLikesMapper).insert(any(ApBehaviorLikes.class));
    }

    @Test
    @DisplayName("点赞文章 - 取消点赞（已点赞过）")
    void testLikeCancel() {
        ApBehaviorLikes existingLike = new ApBehaviorLikes();
        existingLike.setId(1L);
        existingLike.setEntryId(TEST_ARTICLE_ID);
        existingLike.setUserId(TEST_USER_ID);
        existingLike.setType(0);
        existingLike.setOperation(0);

        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apBehaviorLikesMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLike);
        // 设置更新后的文章（点赞数-1）
        ApArticle updatedArticle = new ApArticle();
        updatedArticle.setLikes(9);
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle, updatedArticle);

        ResponseResult result = interactionController.like(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertFalse((Boolean) data.get("liked"));
        assertEquals(9, data.get("diggCount"));
    }

    // ==================== collect ====================

    @Test
    @DisplayName("收藏文章 - 未登录时返回需要登录")
    void testCollectNotLoggedIn() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = interactionController.collect(TEST_ARTICLE_ID);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
    }

    @Test
    @DisplayName("收藏文章 - 文章不存在时返回错误")
    void testCollectArticleNotFound() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(null);

        ResponseResult result = interactionController.collect(TEST_ARTICLE_ID);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    @DisplayName("收藏文章 - 成功收藏（未收藏过）")
    void testCollectSuccess() {
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apCollectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(apCollectionMapper.insert(any(ApCollection.class))).thenReturn(1);
        // 设置更新后的文章（收藏数+1）
        ApArticle updatedArticle = new ApArticle();
        updatedArticle.setCollection(6);
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle, updatedArticle);

        ResponseResult result = interactionController.collect(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertTrue((Boolean) data.get("collected"));
        assertEquals(6, data.get("collectCount"));

        verify(apCollectionMapper).insert(any(ApCollection.class));
    }

    @Test
    @DisplayName("收藏文章 - 取消收藏（已收藏过）")
    void testCollectCancel() {
        ApCollection existingCollect = new ApCollection();
        existingCollect.setId(1L);
        existingCollect.setUserId(TEST_USER_ID);
        existingCollect.setArticleId(TEST_ARTICLE_ID);

        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle);
        when(apCollectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingCollect);
        // 设置更新后的文章（收藏数-1）
        ApArticle updatedArticle = new ApArticle();
        updatedArticle.setCollection(4);
        when(apArticleMapper.selectById(TEST_ARTICLE_ID)).thenReturn(testArticle, updatedArticle);

        ResponseResult result = interactionController.collect(TEST_ARTICLE_ID);
        assertEquals(200, result.getCode());

        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertNotNull(data);
        assertFalse((Boolean) data.get("collected"));
        assertEquals(4, data.get("collectCount"));

        verify(apCollectionMapper).deleteById(1L);
    }
}