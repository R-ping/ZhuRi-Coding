package com.zhuri.coding.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.SysTag;
import com.zhuri.coding.model.user.pojos.UserTagRelation;
import com.zhuri.coding.user.mapper.SysTagMapper;
import com.zhuri.coding.user.mapper.UserTagRelationMapper;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TagSubscribeService 标签订阅管理")
class TagSubscribeServiceImplTest {

    @Mock
    private SysTagMapper sysTagMapper;
    @Mock
    private UserTagRelationMapper userTagRelationMapper;

    @InjectMocks
    private TagSubscribeServiceImpl tagSubscribeService;

    @BeforeEach
    void setUp() {
        ApUser user = new ApUser();
        user.setId(1001);
        user.setNickname("测试用户");
        AppThreadLocalUtil.setUser(user);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Nested
    @DisplayName("发现标签")
    class Discover {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("发现成功：返回标签列表（已登录）")
        void testDiscoverLoggedIn() {
            // Arrange
            List<SysTag> tagList = new ArrayList<>();
            SysTag tag1 = new SysTag();
            tag1.setId(1);
            tag1.setTagName("Java");
            tag1.setCategoryCode("backend");
            tag1.setCategoryName("后端");
            tag1.setSortOrder(100);
            tagList.add(tag1);

            SysTag tag2 = new SysTag();
            tag2.setId(2);
            tag2.setTagName("Spring");
            tag2.setCategoryCode("backend");
            tag2.setCategoryName("后端");
            tag2.setSortOrder(90);
            tagList.add(tag2);

            Page<SysTag> page = mock(Page.class);
            when(page.getRecords()).thenReturn(tagList);
            when(page.getTotal()).thenReturn(2L);
            when(sysTagMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

            when(userTagRelationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            // Act
            ResponseResult result = tagSubscribeService.discover("hot", null, 1, 10);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertTrue(result.getData() instanceof Map);
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(2L, data.get("total"));
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("发现成功：按关键词搜索标签")
        void testDiscoverWithKeyword() {
            // Arrange
            List<SysTag> tagList = new ArrayList<>();
            SysTag tag = new SysTag();
            tag.setId(1);
            tag.setTagName("Java");
            tag.setCategoryCode("backend");
            tag.setCategoryName("后端");
            tagList.add(tag);

            Page<SysTag> page = mock(Page.class);
            when(page.getRecords()).thenReturn(tagList);
            when(page.getTotal()).thenReturn(1L);
            when(sysTagMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

            when(userTagRelationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            // Act
            ResponseResult result = tagSubscribeService.discover("hot", "Java", 1, 10);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("发现成功：未登录用户也能浏览标签列表")
        void testDiscoverNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            List<SysTag> tagList = new ArrayList<>();
            SysTag tag = new SysTag();
            tag.setId(1);
            tag.setTagName("Java");
            tag.setCategoryCode("backend");
            tag.setCategoryName("后端");
            tagList.add(tag);

            Page<SysTag> page = mock(Page.class);
            when(page.getRecords()).thenReturn(tagList);
            when(page.getTotal()).thenReturn(1L);
            when(sysTagMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

            // Act
            ResponseResult result = tagSubscribeService.discover("hot", null, 1, 10);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
        }
    }

    @Nested
    @DisplayName("获取已关注标签")
    class GetFollowed {

        @Test
        @DisplayName("获取成功：返回已关注标签列表")
        void testGetFollowedSuccess() {
            // Arrange
            List<UserTagRelation> relations = new ArrayList<>();
            UserTagRelation relation = new UserTagRelation();
            relation.setUserId(1001L);
            relation.setTagId(1);
            relation.setRelType(2);
            relations.add(relation);

            when(userTagRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);

            SysTag tag = new SysTag();
            tag.setId(1);
            tag.setTagName("Java");
            tag.setCategoryCode("backend");
            tag.setCategoryName("后端");
            when(sysTagMapper.selectById(1)).thenReturn(tag);

            // Act
            ResponseResult result = tagSubscribeService.getFollowed();

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertTrue(result.getData() instanceof List);
        }

        @Test
        @DisplayName("获取失败：未登录")
        void testGetFollowedNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = tagSubscribeService.getFollowed();

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("关注标签")
    class Follow {

        @Test
        @DisplayName("关注成功：新关注")
        void testFollowSuccess() {
            // Arrange
            SysTag tag = new SysTag();
            tag.setId(1);
            tag.setTagName("Java");
            when(sysTagMapper.selectById(1)).thenReturn(tag);
            when(userTagRelationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            // Act
            ResponseResult result = tagSubscribeService.follow(1);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userTagRelationMapper).insert(any(UserTagRelation.class));
        }

        @Test
        @DisplayName("关注成功：已关注过，直接返回成功（幂等）")
        void testFollowAlreadyFollowed() {
            // Arrange
            SysTag tag = new SysTag();
            tag.setId(1);
            tag.setTagName("Java");
            when(sysTagMapper.selectById(1)).thenReturn(tag);
            when(userTagRelationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            // Act
            ResponseResult result = tagSubscribeService.follow(1);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userTagRelationMapper, never()).insert(any(UserTagRelation.class));
        }

        @Test
        @DisplayName("关注失败：标签ID为空")
        void testFollowNullTagId() {
            // Act
            ResponseResult result = tagSubscribeService.follow(null);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("标签ID不能为空", result.getMessage());
        }

        @Test
        @DisplayName("关注失败：标签不存在")
        void testFollowTagNotFound() {
            // Arrange
            when(sysTagMapper.selectById(999)).thenReturn(null);

            // Act
            ResponseResult result = tagSubscribeService.follow(999);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("标签不存在", result.getMessage());
        }

        @Test
        @DisplayName("关注失败：未登录")
        void testFollowNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = tagSubscribeService.follow(1);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("取消关注标签")
    class Unfollow {

        @Test
        @DisplayName("取消关注成功")
        void testUnfollowSuccess() {
            // Act
            ResponseResult result = tagSubscribeService.unfollow(1);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userTagRelationMapper).delete(any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("取消关注失败：标签ID为空")
        void testUnfollowNullTagId() {
            // Act
            ResponseResult result = tagSubscribeService.unfollow(null);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("标签ID不能为空", result.getMessage());
        }

        @Test
        @DisplayName("取消关注失败：未登录")
        void testUnfollowNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = tagSubscribeService.unfollow(1);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }
}