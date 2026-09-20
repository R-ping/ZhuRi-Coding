package com.heima.content.service.draft.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.article.ApArticleDraftMapper;
import com.heima.content.service.draft.DraftManageService;
import com.heima.model.article.pojos.ApArticleDraft;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DraftManageServiceImpl extends ServiceImpl<ApArticleDraftMapper, ApArticleDraft> implements DraftManageService {

    private static final int MAX_DRAFTS = 50;

    @Override
    public ResponseResult list(Long authorId, Integer page, Integer size, String title) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Page<ApArticleDraft> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApArticleDraft> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticleDraft::getAuthorId, userId);
        wrapper.eq(ApArticleDraft::getIsDeleted, false);
        if (title != null && !title.isEmpty()) {
            wrapper.like(ApArticleDraft::getTitle, title);
        }
        wrapper.orderByDesc(ApArticleDraft::getUpdatedTime);
        IPage<ApArticleDraft> result = page(pageParam, wrapper);
        Map<String, Object> data = new HashMap<>();
        data.put("list", result.getRecords());
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteDraft(Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        ApArticleDraft draft = getById(id);
        if (draft == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if (!draft.getAuthorId().equals(user.getId().longValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        draft.setIsDeleted(true);
        updateById(draft);
        return ResponseResult.okResult();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult addDraft(ApArticleDraft draft) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        checkAndTrimDrafts(user.getId().longValue());
        draft.setAuthorId(user.getId().longValue());
        draft.setIsDeleted(false);
        draft.setCreatedTime(new Date());
        draft.setUpdatedTime(new Date());
        save(draft);
        return ResponseResult.okResult(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateDraft(ApArticleDraft draft) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (draft.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        ApArticleDraft existing = getById(draft.getId());
        if (existing == null || existing.getIsDeleted()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if (!existing.getAuthorId().equals(user.getId().longValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        draft.setAuthorId(user.getId().longValue());
        draft.setUpdatedTime(new Date());
        updateById(draft);
        return ResponseResult.okResult(draft);
    }

    private void checkAndTrimDrafts(Long authorId) {
        LambdaQueryWrapper<ApArticleDraft> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApArticleDraft::getAuthorId, authorId);
        wrapper.eq(ApArticleDraft::getIsDeleted, false);
        wrapper.orderByAsc(ApArticleDraft::getUpdatedTime);
        List<ApArticleDraft> drafts = list(wrapper);
        if (drafts.size() >= MAX_DRAFTS) {
            int toDelete = drafts.size() - MAX_DRAFTS + 1;
            for (int i = 0; i < toDelete; i++) {
                ApArticleDraft oldDraft = drafts.get(i);
                oldDraft.setIsDeleted(true);
                updateById(oldDraft);
            }
        }
    }
}