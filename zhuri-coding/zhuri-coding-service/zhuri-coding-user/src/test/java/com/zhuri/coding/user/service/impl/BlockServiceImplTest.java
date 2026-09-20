package com.heima.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dto.BlockDTO;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.SysTag;
import com.heima.model.user.pojos.UserBlockRelation;
import com.heima.user.mapper.ApUserMapper;
import com.heima.user.mapper.SysTagMapper;
import com.heima.user.mapper.UserBlockRelationMapper;
import com.heima.utils.thread.AppThreadLocalUtil;
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
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockService 用户屏蔽管理")
class BlockServiceImplTest {

    @Mock
    private UserBlockRelationMapper userBlockRelationMapper;
    @Mock
    private ApUserMapper apUserMapper;
    @Mock
    private SysTagMapper sysTagMapper;

    @InjectMocks
    private BlockServiceImpl blockService;

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
    @DisplayName("获取屏蔽列表")
    class GetBlocks {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("获取成功：屏蔽作者列表")
        void testGetBlocksAuthorType() {
            // Arrange
            List<UserBlockRelation> records = new ArrayList<>();
            UserBlockRelation block = new UserBlockRelation();
            block.setId(1L);
            block.setTargetType(1);
            block.setTargetId(2001L);
            block.setCreateTime(new Date());
            records.add(block);

            Page<UserBlockRelation> page = mock(Page.class);
            when(page.getRecords()).thenReturn(records);
            when(page.getTotal()).thenReturn(1L);
            when(userBlockRelationMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(page);

            ApUser author = new ApUser();
            author.setNickname("被屏蔽作者");
            author.setImage("author-avatar.jpg");
            when(apUserMapper.selectById(2001)).thenReturn(author);

            // Act
            ResponseResult result = blockService.getBlocks(1, 1, 10);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            assertTrue(result.getData() instanceof Map);
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(1L, data.get("total"));
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("获取成功：屏蔽标签列表")
        void testGetBlocksTagType() {
            // Arrange
            List<UserBlockRelation> records = new ArrayList<>();
            UserBlockRelation block = new UserBlockRelation();
            block.setId(2L);
            block.setTargetType(2);
            block.setTargetId(3001L);
            block.setCreateTime(new Date());
            records.add(block);

            Page<UserBlockRelation> page = mock(Page.class);
            when(page.getRecords()).thenReturn(records);
            when(page.getTotal()).thenReturn(1L);
            when(userBlockRelationMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(page);

            SysTag tag = new SysTag();
            tag.setTagName("Java");
            when(sysTagMapper.selectById(3001)).thenReturn(tag);

            // Act
            ResponseResult result = blockService.getBlocks(2, 1, 10);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("获取失败：无效的屏蔽类型")
        void testGetBlocksInvalidType() {
            // Act
            ResponseResult result = blockService.getBlocks(3, 1, 10);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("无效的屏蔽类型", result.getMessage());
        }

        @Test
        @DisplayName("获取失败：未登录")
        void testGetBlocksNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = blockService.getBlocks(1, 1, 10);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("添加屏蔽")
    class AddBlock {

        @Test
        @DisplayName("添加成功：屏蔽作者")
        void testAddBlockAuthorSuccess() {
            // Arrange
            BlockDTO dto = new BlockDTO();
            dto.setType(1);
            dto.setTargetId(2001L);

            when(userBlockRelationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            // Act
            ResponseResult result = blockService.addBlock(dto);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userBlockRelationMapper).insert(any(UserBlockRelation.class));
        }

        @Test
        @DisplayName("添加失败：已屏蔽，无需重复操作")
        void testAddBlockAlreadyExists() {
            // Arrange
            BlockDTO dto = new BlockDTO();
            dto.setType(1);
            dto.setTargetId(2001L);

            when(userBlockRelationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            // Act
            ResponseResult result = blockService.addBlock(dto);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("已屏蔽，无需重复操作", result.getMessage());
            verify(userBlockRelationMapper, never()).insert(any(UserBlockRelation.class));
        }

        @Test
        @DisplayName("添加失败：无效的屏蔽类型")
        void testAddBlockInvalidType() {
            // Arrange
            BlockDTO dto = new BlockDTO();
            dto.setType(3);
            dto.setTargetId(2001L);

            // Act
            ResponseResult result = blockService.addBlock(dto);

            // Assert
            assertEquals(503, result.getCode());
        }

        @Test
        @DisplayName("添加失败：targetId为空")
        void testAddBlockNullTargetId() {
            // Arrange
            BlockDTO dto = new BlockDTO();
            dto.setType(1);
            dto.setTargetId(null);

            // Act
            ResponseResult result = blockService.addBlock(dto);

            // Assert
            assertEquals(503, result.getCode());
        }

        @Test
        @DisplayName("添加失败：未登录")
        void testAddBlockNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();
            BlockDTO dto = new BlockDTO();
            dto.setType(1);
            dto.setTargetId(2001L);

            // Act
            ResponseResult result = blockService.addBlock(dto);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }

    @Nested
    @DisplayName("移除屏蔽")
    class RemoveBlock {

        @Test
        @DisplayName("移除成功：屏蔽记录存在且属于当前用户")
        void testRemoveBlockSuccess() {
            // Arrange
            UserBlockRelation relation = new UserBlockRelation();
            relation.setId(1L);
            relation.setUserId(1001L);
            when(userBlockRelationMapper.selectById(1L)).thenReturn(relation);

            // Act
            ResponseResult result = blockService.removeBlock(1L);

            // Assert
            assertNotNull(result);
            assertEquals(200, result.getCode());
            verify(userBlockRelationMapper).deleteById(1L);
        }

        @Test
        @DisplayName("移除失败：屏蔽记录不存在")
        void testRemoveBlockNotFound() {
            // Arrange
            when(userBlockRelationMapper.selectById(999L)).thenReturn(null);

            // Act
            ResponseResult result = blockService.removeBlock(999L);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("屏蔽记录不存在", result.getMessage());
        }

        @Test
        @DisplayName("移除失败：无权操作他人的屏蔽记录")
        void testRemoveBlockNotOwner() {
            // Arrange
            UserBlockRelation relation = new UserBlockRelation();
            relation.setId(1L);
            relation.setUserId(9999L);
            when(userBlockRelationMapper.selectById(1L)).thenReturn(relation);

            // Act
            ResponseResult result = blockService.removeBlock(1L);

            // Assert
            assertEquals(503, result.getCode());
            assertEquals("无权操作该屏蔽记录", result.getMessage());
        }

        @Test
        @DisplayName("移除失败：ID为空")
        void testRemoveBlockNullId() {
            // Act
            ResponseResult result = blockService.removeBlock(null);

            // Assert
            assertEquals(503, result.getCode());
        }

        @Test
        @DisplayName("移除失败：未登录")
        void testRemoveBlockNotLoggedIn() {
            // Arrange
            AppThreadLocalUtil.clear();

            // Act
            ResponseResult result = blockService.removeBlock(1L);

            // Assert
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode());
        }
    }
}