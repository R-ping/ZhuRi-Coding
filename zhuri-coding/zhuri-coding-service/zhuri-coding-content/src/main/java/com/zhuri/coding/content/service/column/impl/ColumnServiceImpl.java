package com.heima.content.service.column.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.content.mapper.column.ApColumnMapper;
import com.heima.content.service.column.ColumnService;
import com.heima.model.column.pojos.ApColumn;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ColumnServiceImpl extends ServiceImpl<ApColumnMapper, ApColumn> implements ColumnService {

    @Autowired
    private ColumnAuditService columnAuditService;

    @Override
    public ResponseResult list(Long authorId, Integer page, Integer size, String status, String title) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Page<ApColumn> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ApColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        if (status != null && !status.isEmpty()) {
            Byte statusCode = getStatusCode(status);
            if (statusCode != null) {
                wrapper.eq(ApColumn::getStatus, statusCode);
            }
        }
        if (title != null && !title.isEmpty()) {
            wrapper.like(ApColumn::getTitle, title);
        }
        wrapper.orderByDesc(ApColumn::getCreatedTime);
        IPage<ApColumn> result = page(pageParam, wrapper);
        Map<String, Object> data = new HashMap<>();
        data.put("list", result.getRecords());
        data.put("total", result.getTotal());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult statistics(Long authorId) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = authorId != null ? authorId : user.getId().longValue();
        Map<String, Object> data = new HashMap<>();
        LambdaQueryWrapper<ApColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        data.put("total", count(wrapper));
        wrapper.eq(ApColumn::getStatus, ApColumn.Status.PUBLISHED.getCode());
        data.put("published", count(wrapper));
        wrapper.clear();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        wrapper.eq(ApColumn::getStatus, ApColumn.Status.SUBMIT.getCode());
        data.put("reviewing", count(wrapper));
        wrapper.clear();
        wrapper.eq(ApColumn::getAuthorId, userId);
        wrapper.eq(ApColumn::getIsDeleted, false);
        wrapper.eq(ApColumn::getStatus, ApColumn.Status.FAIL.getCode());
        data.put("rejected", count(wrapper));
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createColumn(ApColumn column) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (column.getTitle() == null || column.getTitle().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "专栏名称不能为空");
        }
        if (column.getDescription() == null || column.getDescription().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "专栏简介不能为空");
        }
        column.setAuthorId(user.getId().longValue());
        column.setAuthorName(user.getNickname());
        column.setAuthorImage(user.getImage());
        column.setIsDeleted(false);
        column.setStatus(ApColumn.Status.SUBMIT.getCode());
        column.setArticleCount(0);
        column.setSubscribeCount(0);
        column.setCreatedTime(new Date());
        column.setUpdatedTime(new Date());
        save(column);

        // 异步审核专栏
        asyncReviewColumn(column);

        return ResponseResult.okResult(column);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult updateColumn(ApColumn column) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (column.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        ApColumn existing = getById(column.getId());
        if (existing == null || existing.getIsDeleted()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if (!existing.getAuthorId().equals(user.getId().longValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if (column.getTitle() != null) {
            existing.setTitle(column.getTitle());
        }
        if (column.getDescription() != null) {
            existing.setDescription(column.getDescription());
        }
        if (column.getCoverImage() != null) {
            existing.setCoverImage(column.getCoverImage());
        }
        existing.setUpdatedTime(new Date());
        updateById(existing);
        return ResponseResult.okResult(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult deleteColumn(Long id) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (id == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "id 不能为空");
        }
        ApColumn column = getById(id);
        if (column == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if (!column.getAuthorId().equals(user.getId().longValue())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        column.setIsDeleted(true);
        updateById(column);
        return ResponseResult.okResult();
    }

    /**
     * 异步审核专栏
     */
    @Async
    public void asyncReviewColumn(ApColumn column) {
        try {
            log.info("开始异步审核专栏, columnId={}", column.getId());

            // 构建审核上下文
            AuditContext auditContext = new AuditContext(AuditEntityType.COLUMN, column.getId(), column.getAuthorId());
            auditContext.withTitle(column.getTitle() != null ? column.getTitle() : "")
                .withContent(column.getDescription() != null ? column.getDescription() : "")
                .withAuthorName(column.getAuthorName());

            // 封面图片也需审核
            if (column.getCoverImage() != null && !column.getCoverImage().isEmpty()) {
                auditContext.withImageUrl(column.getCoverImage());
            }

            // 执行审核
            columnAuditService.audit(auditContext);
            log.info("专栏审核完成, columnId={}", column.getId());
        } catch (Exception e) {
            log.error("专栏审核异常, columnId={}", column.getId(), e);
        }
    }

    private Byte getStatusCode(String status) {
        switch (status) {
            case "published":
                return ApColumn.Status.PUBLISHED.getCode();
            case "reviewing":
                return ApColumn.Status.SUBMIT.getCode();
            case "rejected":
                return ApColumn.Status.FAIL.getCode();
            default:
                return null;
        }
    }
}