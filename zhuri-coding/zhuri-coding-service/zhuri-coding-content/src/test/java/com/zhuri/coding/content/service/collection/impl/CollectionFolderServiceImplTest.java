package com.zhuri.coding.content.service.collection.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionFolderMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.model.behavior.pojos.ApCollectionFolder;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CollectionFolderServiceImpl 单元测试（F4 收藏夹管理）
 *
 * 覆盖：列表统计合并、创建（名称/上限/重名）、重命名、删除回退、相邻排序、归属校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("收藏夹管理服务单元测试")
class CollectionFolderServiceImplTest {

    private static final Integer USER_ID = 1001;
    private static final Integer OTHER_USER_ID = 2002;

    @Mock
    private ApCollectionFolderMapper folderMapper;
    @Mock
    private ApCollectionMapper collectionMapper;

    @InjectMocks
    private CollectionFolderServiceImpl service;

    // ---------- 辅助 ----------

    private ApCollectionFolder folder(Long id, Integer userId, String name, int sortOrder) {
        ApCollectionFolder f = new ApCollectionFolder();
        f.setId(id);
        f.setUserId(userId);
        f.setName(name);
        f.setSortOrder(sortOrder);
        f.setCreatedTime(new Date());
        f.setUpdatedTime(new Date());
        return f;
    }

    // ==================== listFolders ====================

    @Test
    @DisplayName("listFolders - 合并收藏夹与条目统计（含最近使用时间）")
    void testListFoldersMergeStats() {
        when(folderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(folder(1L, USER_ID, "面试八股", 0), folder(2L, USER_ID, "源码阅读", 1)));
        Date lastUsed = new Date(1700000000000L);
        Map<String, Object> stat = new HashMap<>();
        stat.put("folderId", 1L);
        stat.put("articleCount", 3L);
        stat.put("lastUsedTime", lastUsed);
        when(folderMapper.selectFolderStats(USER_ID)).thenReturn(List.of(stat));

        ResponseResult result = service.listFolders(USER_ID);

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.getData();
        assertEquals(2, list.size());

        Map<String, Object> first = list.get(0);
        assertEquals(1L, first.get("id"));
        assertEquals("面试八股", first.get("name"));
        assertEquals(3L, first.get("articleCount"));
        assertEquals(lastUsed.getTime(), first.get("lastUsedTime"));

        // 无收藏的收藏夹：条目为 0、最近使用时间为空
        Map<String, Object> second = list.get(1);
        assertEquals(0L, second.get("articleCount"));
        assertNull(second.get("lastUsedTime"));
    }

    @Test
    @DisplayName("listFolders - 未登录返回 NEED_LOGIN")
    void testListFoldersNeedLogin() {
        ResponseResult result = service.listFolders(null);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), result.getCode().intValue());
        verify(folderMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    // ==================== createFolder ====================

    @Test
    @DisplayName("createFolder - 名称为空或超长返回参数错误")
    void testCreateFolderNameInvalid() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.createFolder(USER_ID, "   ").getCode().intValue());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.createFolder(USER_ID, "一二三四五六七八九十一二三四五六七八九十一").getCode().intValue());
        verify(folderMapper, never()).insert(any(ApCollectionFolder.class));
    }

    @Test
    @DisplayName("createFolder - 超过数量上限返回参数错误")
    void testCreateFolderLimitExceeded() {
        when(folderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(50L);

        ResponseResult result = service.createFolder(USER_ID, "第51个");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("上限"));
        verify(folderMapper, never()).insert(any(ApCollectionFolder.class));
    }

    @Test
    @DisplayName("createFolder - 创建成功：名称 trim、排序值取最大值+1")
    void testCreateFolderSuccess() {
        when(folderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(folderMapper.selectMaxSortOrder(USER_ID)).thenReturn(4);
        doAnswer(inv -> {
            ApCollectionFolder f = inv.getArgument(0);
            f.setId(99L);
            return 1;
        }).when(folderMapper).insert(any(ApCollectionFolder.class));

        ResponseResult result = service.createFolder(USER_ID, "  面试八股  ");

        assertEquals(200, result.getCode().intValue());
        ArgumentCaptor<ApCollectionFolder> captor = ArgumentCaptor.forClass(ApCollectionFolder.class);
        verify(folderMapper).insert(captor.capture());
        ApCollectionFolder saved = captor.getValue();
        assertEquals("面试八股", saved.getName());
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(5, saved.getSortOrder());
        assertNotNull(saved.getCreatedTime());
        assertNotNull(saved.getUpdatedTime());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(99L, data.get("id"));
    }

    @Test
    @DisplayName("createFolder - 唯一索引冲突返回同名提示")
    void testCreateFolderDuplicateName() {
        when(folderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(folderMapper.selectMaxSortOrder(USER_ID)).thenReturn(0);
        when(folderMapper.insert(any(ApCollectionFolder.class)))
                .thenThrow(new DuplicateKeyException("uk_folder_user_name"));

        ResponseResult result = service.createFolder(USER_ID, "面试八股");

        assertEquals(AppHttpCodeEnum.DATA_EXIST.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("同名"));
    }

    // ==================== renameFolder ====================

    @Test
    @DisplayName("renameFolder - 收藏夹不存在或不属于当前用户返回不存在")
    void testRenameFolderNotFoundOrNotOwner() {
        when(folderMapper.selectById(7L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.renameFolder(USER_ID, 7L, "新名字").getCode().intValue());

        when(folderMapper.selectById(8L)).thenReturn(folder(8L, OTHER_USER_ID, "别人的", 0));
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.renameFolder(USER_ID, 8L, "新名字").getCode().intValue());
        verify(folderMapper, never()).updateById(any(ApCollectionFolder.class));
    }

    @Test
    @DisplayName("renameFolder - 改名成功")
    void testRenameFolderSuccess() {
        ApCollectionFolder existing = folder(7L, USER_ID, "旧名字", 0);
        when(folderMapper.selectById(7L)).thenReturn(existing);
        when(folderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ResponseResult result = service.renameFolder(USER_ID, 7L, "新名字");

        assertEquals(200, result.getCode().intValue());
        ArgumentCaptor<ApCollectionFolder> captor = ArgumentCaptor.forClass(ApCollectionFolder.class);
        verify(folderMapper).updateById(captor.capture());
        assertEquals("新名字", captor.getValue().getName());
    }

    @Test
    @DisplayName("renameFolder - 与已有收藏夹同名返回错误")
    void testRenameFolderDuplicateName() {
        when(folderMapper.selectById(7L)).thenReturn(folder(7L, USER_ID, "旧名字", 0));
        when(folderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        ResponseResult result = service.renameFolder(USER_ID, 7L, "已存在");

        assertEquals(AppHttpCodeEnum.DATA_EXIST.getCode(), result.getCode().intValue());
        verify(folderMapper, never()).updateById(any(ApCollectionFolder.class));
    }

    @Test
    @DisplayName("renameFolder - 名称未变化时不写库")
    void testRenameFolderSameNameNoop() {
        when(folderMapper.selectById(7L)).thenReturn(folder(7L, USER_ID, "同一个", 0));

        ResponseResult result = service.renameFolder(USER_ID, 7L, "同一个");

        assertEquals(200, result.getCode().intValue());
        verify(folderMapper, never()).updateById(any(ApCollectionFolder.class));
        verify(folderMapper, never()).selectCount(any(LambdaQueryWrapper.class));
    }

    // ==================== removeFolder ====================

    @Test
    @DisplayName("removeFolder - 删除成功：收藏回退默认收藏夹后删除收藏夹")
    void testRemoveFolderSuccess() {
        when(folderMapper.selectById(7L)).thenReturn(folder(7L, USER_ID, "待删", 0));
        when(collectionMapper.resetFolder(USER_ID, 7L)).thenReturn(2);

        ResponseResult result = service.removeFolder(USER_ID, 7L);

        assertEquals(200, result.getCode().intValue());
        verify(collectionMapper).resetFolder(USER_ID, 7L);
        verify(folderMapper).deleteById(7L);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2, data.get("movedCount"));
    }

    @Test
    @DisplayName("removeFolder - 不属于当前用户时拒绝且不触碰收藏数据")
    void testRemoveFolderNotOwner() {
        when(folderMapper.selectById(8L)).thenReturn(folder(8L, OTHER_USER_ID, "别人的", 0));

        ResponseResult result = service.removeFolder(USER_ID, 8L);

        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode().intValue());
        verify(collectionMapper, never()).resetFolder(any(), any());
        verify(folderMapper, never()).deleteById(any(Long.class));
    }

    // ==================== moveFolder ====================

    @Test
    @DisplayName("moveFolder - 上移与相邻收藏夹交换并按新顺序重排")
    void testMoveFolderUpSwap() {
        ApCollectionFolder a = folder(10L, USER_ID, "A", 0);
        ApCollectionFolder b = folder(20L, USER_ID, "B", 1);
        when(folderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>(List.of(a, b)));

        ResponseResult result = service.moveFolder(USER_ID, 20L, "up");

        assertEquals(200, result.getCode().intValue());
        ArgumentCaptor<ApCollectionFolder> captor = ArgumentCaptor.forClass(ApCollectionFolder.class);
        verify(folderMapper, times(2)).updateById(captor.capture());
        List<ApCollectionFolder> updated = captor.getAllValues();
        assertEquals(20L, updated.get(0).getId());
        assertEquals(0, updated.get(0).getSortOrder());
        assertEquals(10L, updated.get(1).getId());
        assertEquals(1, updated.get(1).getSortOrder());
    }

    @Test
    @DisplayName("moveFolder - 已在首位上移为 no-op")
    void testMoveFolderAtTopNoop() {
        ApCollectionFolder a = folder(10L, USER_ID, "A", 0);
        ApCollectionFolder b = folder(20L, USER_ID, "B", 1);
        when(folderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>(List.of(a, b)));

        ResponseResult result = service.moveFolder(USER_ID, 10L, "up");

        assertEquals(200, result.getCode().intValue());
        verify(folderMapper, never()).updateById(any(ApCollectionFolder.class));
    }

    @Test
    @DisplayName("moveFolder - 排序方向不合法返回参数错误")
    void testMoveFolderInvalidDirection() {
        ResponseResult result = service.moveFolder(USER_ID, 10L, "left");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        verify(folderMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    // ==================== resolveValidFolderId ====================

    @Test
    @DisplayName("resolveValidFolderId - 有效归属返回原值，无效返回空")
    void testResolveValidFolderId() {
        assertNull(service.resolveValidFolderId(USER_ID, null));
        assertNull(service.resolveValidFolderId(null, 7L));

        when(folderMapper.selectById(7L)).thenReturn(folder(7L, USER_ID, "有效", 0));
        assertEquals(7L, service.resolveValidFolderId(USER_ID, 7L));

        when(folderMapper.selectById(8L)).thenReturn(folder(8L, OTHER_USER_ID, "别人的", 0));
        assertNull(service.resolveValidFolderId(USER_ID, 8L));

        when(folderMapper.selectById(9L)).thenReturn(null);
        assertNull(service.resolveValidFolderId(USER_ID, 9L));
    }
}