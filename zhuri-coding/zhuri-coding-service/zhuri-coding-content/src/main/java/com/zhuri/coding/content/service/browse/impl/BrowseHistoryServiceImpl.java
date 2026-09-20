package com.zhuri.coding.content.service.browse.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhuri.coding.content.mapper.interaction.ApBrowseHistoryMapper;
import com.zhuri.coding.content.service.browse.BrowseHistoryService;
import com.zhuri.coding.model.behavior.pojos.ApBrowseHistory;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class BrowseHistoryServiceImpl extends ServiceImpl<ApBrowseHistoryMapper, ApBrowseHistory> implements BrowseHistoryService {

    @Override
    public ResponseResult getHistoryList(Long userId, Integer page, Integer size, String keyword) {
        LambdaQueryWrapper<ApBrowseHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApBrowseHistory::getUserId, userId);
        wrapper.eq(ApBrowseHistory::getIsDeleted, false);
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(ApBrowseHistory::getArticleTitle, keyword.trim());
        }
        wrapper.orderByDesc(ApBrowseHistory::getBrowseTime);

        IPage<ApBrowseHistory> pageResult = page(new Page<>(page, size), wrapper);

        List<ApBrowseHistory> records = pageResult.getRecords();
        List<Map<String, Object>> flatList = new ArrayList<>();
        for (ApBrowseHistory record : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", record.getId() != null ? String.valueOf(record.getId()) : "");
            item.put("targetType", record.getTargetType());
            item.put("articleId", record.getArticleId() != null ? String.valueOf(record.getArticleId()) : "");
            item.put("articleTitle", record.getArticleTitle());
            item.put("authorName", record.getAuthorName());
            item.put("authorAvatar", record.getAuthorAvatar());
            item.put("summary", record.getSummary());
            item.put("readCount", record.getReadCount());
            item.put("likeCount", record.getLikeCount());
            item.put("commentCount", record.getCommentCount());
            item.put("browseTime", record.getBrowseTime());
            flatList.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", flatList);
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        result.put("size", size);

        return ResponseResult.okResult(result);
    }

    @Override
    public void clearHistory(Long userId) {
        LambdaUpdateWrapper<ApBrowseHistory> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ApBrowseHistory::getUserId, userId);
        wrapper.set(ApBrowseHistory::getIsDeleted, true);
        wrapper.set(ApBrowseHistory::getDeletedAt, new Date());
        update(wrapper);
    }

    @Override
    public ResponseResult reportBrowse(Long userId, Integer targetType, Long targetId) {
        if (userId == null || targetType == null || targetId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }

        // 查找是否存在同一用户的相同类型和内容的浏览记录（未删除）
        LambdaQueryWrapper<ApBrowseHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApBrowseHistory::getUserId, userId);
        wrapper.eq(ApBrowseHistory::getTargetType, targetType);
        wrapper.eq(ApBrowseHistory::getArticleId, targetId);
        wrapper.eq(ApBrowseHistory::getIsDeleted, false);
        ApBrowseHistory existing = getOne(wrapper);

        if (existing != null) {
            // 已存在，更新浏览时间
            existing.setBrowseTime(new Date());
            updateById(existing);
        } else {
            // 不存在，插入新记录
            ApBrowseHistory record = new ApBrowseHistory();
            record.setUserId(userId);
            record.setTargetType(targetType);
            record.setArticleId(targetId);
            record.setBrowseTime(new Date());
            record.setIsDeleted(false);
            save(record);
        }

        return ResponseResult.okResult();
    }
}