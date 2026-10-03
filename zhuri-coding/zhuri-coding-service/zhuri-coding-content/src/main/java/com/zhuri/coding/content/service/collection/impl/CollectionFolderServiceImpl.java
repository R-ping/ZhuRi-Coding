package com.zhuri.coding.content.service.collection.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionFolderMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.service.collection.CollectionFolderService;
import com.zhuri.coding.model.behavior.pojos.ApCollectionFolder;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收藏夹管理实现（F4）。
 *
 * <p><b>排序实现</b>：与相邻收藏夹"交换 + 重排"——查出当前顺序后在内存中移动目标项，
 * 再按新顺序回写 sort_order（上限 50 条，开销可忽略）。相比相邻两项交换值更稳：
 * 历史数据 sort_order 重复（均为默认 0）时交换会失效，重排则天然幂等。</p>
 *
 * <p><b>并发口径</b>：同用户重名由 {@code uk_folder_user_name} 唯一索引兜底，
 * 应用层先查重是为了给出友好提示；数量上限（50）不做数据库约束（运营规则，随时可能调整），
 * 极端并发下允许轻微超出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionFolderServiceImpl implements CollectionFolderService {

    /** 单用户收藏夹数量上限（PRD F4 规则） */
    private static final int MAX_FOLDERS_PER_USER = 50;
    /** 收藏夹名称长度上限（PRD F4 规则：1-20 字） */
    private static final int MAX_NAME_LENGTH = 20;

    private static final String SORT_DIRECTION_UP = "up";
    private static final String SORT_DIRECTION_DOWN = "down";

    private final ApCollectionFolderMapper folderMapper;
    private final ApCollectionMapper collectionMapper;

    @Override
    public ResponseResult listFolders(Integer userId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        List<ApCollectionFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<ApCollectionFolder>()
                        .eq(ApCollectionFolder::getUserId, userId)
                        .orderByAsc(ApCollectionFolder::getSortOrder)
                        .orderByAsc(ApCollectionFolder::getId));

        // 条目数与最近使用时间一次分组查出（避免逐夹 count 的 N+1）
        Map<Long, Map<String, Object>> statMap = new HashMap<>();
        List<Map<String, Object>> stats = folderMapper.selectFolderStats(userId);
        if (stats != null) {
            for (Map<String, Object> stat : stats) {
                Object fid = stat.get("folderId");
                if (fid instanceof Number) {
                    statMap.put(((Number) fid).longValue(), stat);
                }
            }
        }

        List<Map<String, Object>> list = new ArrayList<>(folders.size());
        for (ApCollectionFolder folder : folders) {
            list.add(toVo(folder, statMap.get(folder.getId())));
        }
        return ResponseResult.okResult(list);
    }

    @Override
    public ResponseResult createFolder(Integer userId, String name) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        String normalized = normalizeName(name);
        if (normalized == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "收藏夹名称需在1-20字之间");
        }
        Long count = folderMapper.selectCount(new LambdaQueryWrapper<ApCollectionFolder>()
                .eq(ApCollectionFolder::getUserId, userId));
        if (count != null && count >= MAX_FOLDERS_PER_USER) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "收藏夹数量已达上限（最多" + MAX_FOLDERS_PER_USER + "个）");
        }

        Date now = new Date();
        ApCollectionFolder folder = new ApCollectionFolder();
        folder.setUserId(userId);
        folder.setName(normalized);
        Integer maxSort = folderMapper.selectMaxSortOrder(userId);
        folder.setSortOrder(maxSort == null ? 0 : maxSort + 1);
        folder.setCreatedTime(now);
        folder.setUpdatedTime(now);
        try {
            folderMapper.insert(folder);
        } catch (DuplicateKeyException e) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_EXIST, "已存在同名收藏夹");
        }
        log.info("[CollectionFolder] 创建收藏夹, userId={}, folderId={}, name={}", userId, folder.getId(), normalized);
        return ResponseResult.okResult(toVo(folder, null));
    }

    @Override
    public ResponseResult renameFolder(Integer userId, Long folderId, String name) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (folderId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "收藏夹ID不能为空");
        }
        String normalized = normalizeName(name);
        if (normalized == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "收藏夹名称需在1-20字之间");
        }
        ApCollectionFolder folder = folderMapper.selectById(folderId);
        if (folder == null || !userId.equals(folder.getUserId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "收藏夹不存在");
        }
        if (normalized.equals(folder.getName())) {
            return ResponseResult.okResult(toVo(folder, null)); // 名称未变化，直接返回
        }
        Long dup = folderMapper.selectCount(new LambdaQueryWrapper<ApCollectionFolder>()
                .eq(ApCollectionFolder::getUserId, userId)
                .eq(ApCollectionFolder::getName, normalized)
                .ne(ApCollectionFolder::getId, folderId));
        if (dup != null && dup > 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_EXIST, "已存在同名收藏夹");
        }
        folder.setName(normalized);
        folder.setUpdatedTime(new Date());
        try {
            folderMapper.updateById(folder);
        } catch (DuplicateKeyException e) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_EXIST, "已存在同名收藏夹");
        }
        log.info("[CollectionFolder] 重命名收藏夹, userId={}, folderId={}, name={}", userId, folderId, normalized);
        return ResponseResult.okResult(toVo(folder, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult removeFolder(Integer userId, Long folderId) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (folderId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "收藏夹ID不能为空");
        }
        ApCollectionFolder folder = folderMapper.selectById(folderId);
        if (folder == null || !userId.equals(folder.getUserId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "收藏夹不存在");
        }
        // 其中收藏回退"默认收藏夹"（folder_id 置空），收藏记录本身不删除（PRD F4 规则）
        int moved = collectionMapper.resetFolder(userId, folderId);
        folderMapper.deleteById(folderId);
        log.info("[CollectionFolder] 删除收藏夹, userId={}, folderId={}, 回退默认收藏数={}", userId, folderId, moved);

        Map<String, Object> data = new HashMap<>();
        data.put("movedCount", moved);
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult moveFolder(Integer userId, Long folderId, String direction) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (folderId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "收藏夹ID不能为空");
        }
        boolean up = SORT_DIRECTION_UP.equalsIgnoreCase(direction);
        boolean down = SORT_DIRECTION_DOWN.equalsIgnoreCase(direction);
        if (!up && !down) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "排序方向不合法");
        }
        List<ApCollectionFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<ApCollectionFolder>()
                        .eq(ApCollectionFolder::getUserId, userId)
                        .orderByAsc(ApCollectionFolder::getSortOrder)
                        .orderByAsc(ApCollectionFolder::getId));
        int index = -1;
        for (int i = 0; i < folders.size(); i++) {
            if (folderId.equals(folders.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "收藏夹不存在");
        }
        int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= folders.size()) {
            return ResponseResult.okResult(); // 已在首/末位，保持不动
        }
        Collections.swap(folders, index, target);

        // 按新顺序重排 sort_order，仅更新值发生变化的行
        Date now = new Date();
        for (int i = 0; i < folders.size(); i++) {
            ApCollectionFolder f = folders.get(i);
            if (f.getSortOrder() == null || f.getSortOrder() != i) {
                f.setSortOrder(i);
                f.setUpdatedTime(now);
                folderMapper.updateById(f);
            }
        }
        return ResponseResult.okResult();
    }

    @Override
    public Long resolveValidFolderId(Integer userId, Long folderId) {
        if (userId == null || folderId == null) {
            return null;
        }
        ApCollectionFolder folder = folderMapper.selectById(folderId);
        if (folder == null || !userId.equals(folder.getUserId())) {
            // 目标收藏夹已被删除或不属于当前用户 → 回退默认收藏夹（PRD F4 边界）
            return null;
        }
        return folderId;
    }

    /** 名称规整：trim 后校验 1-20 字，返回 null 表示非法 */
    private String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            return null;
        }
        return trimmed;
    }

    /** 组装收藏夹 VO（含条目数与最近使用时间；无收藏时 articleCount=0、lastUsedTime=null） */
    private Map<String, Object> toVo(ApCollectionFolder folder, Map<String, Object> stat) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", folder.getId());
        vo.put("name", folder.getName());
        vo.put("sortOrder", folder.getSortOrder() != null ? folder.getSortOrder() : 0);
        long articleCount = 0L;
        Long lastUsedTime = null;
        if (stat != null) {
            Object cnt = stat.get("articleCount");
            if (cnt instanceof Number) {
                articleCount = ((Number) cnt).longValue();
            }
            Object last = stat.get("lastUsedTime");
            if (last instanceof Date) {
                lastUsedTime = ((Date) last).getTime();
            }
        }
        vo.put("articleCount", articleCount);
        vo.put("lastUsedTime", lastUsedTime);
        vo.put("createdTime", folder.getCreatedTime());
        return vo;
    }
}