package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.service.admin.AdminContentFoldService;
import com.zhuri.coding.content.service.admin.AdminContentNotifier;
import com.zhuri.coding.model.admin.AdminContentType;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminCommentVO;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运营侧「内容折叠」实现。
 *
 * <p>折叠与下架最本质的区别是**可逆性**：折叠只动一个标记位，改回来就恢复；
 * 下架要联动清 ES 与 PG 向量，reverse 成本高得多。所以折叠这条路上不需要本地消息表去
 * 保证"副作用一定完成"—— 唯一的副作用是站内信，它已经由
 * {@link AdminContentNotifier} 自己挂上了本地消息表。
 *
 * <p><b>幂等闸口是条件更新而不是先查后改</b>：{@code WHERE id = ? AND is_hidden = 0}
 * 让两个运营同时点"折叠"时只有一个能改到行，另一个 affected = 0 → 明确告知"已是折叠状态"。
 * 先 SELECT 再 UPDATE 的写法在并发下两边都能读到 0、都能"成功"，
 * 报表上看起来做了两次折叠，谁也说不清是谁做的。
 */
@Slf4j
@Service
public class AdminContentFoldServiceImpl implements AdminContentFoldService {

    /** 单页上限：折叠复核是人工操作，不需要一次拉几百条，同时拦住前端误传 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** is_hidden 取值：正常 */
    private static final int HIDDEN_NO = 0;
    /** is_hidden 取值：已折叠 */
    private static final int HIDDEN_YES = 1;

    @Autowired
    private ApCommentMapper commentMapper;

    @Autowired
    private ApPinsCommentMapper pinsCommentMapper;

    @Autowired
    private AdminContentNotifier notifier;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    @Override
    public ResponseResult page(AdminContentType type, Integer hidden, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;

        List<AdminCommentVO> list;
        long total;
        if (type == AdminContentType.COMMENT) {
            // 显式投影：评论正文可能是长文本，其余字段（图片、计数）这里一概不用
            var wrapper = new LambdaQueryWrapper<ApComment>()
                .select(ApComment::getId, ApComment::getArticleId, ApComment::getUserId,
                    ApComment::getUserName, ApComment::getContent, ApComment::getIsHidden,
                    ApComment::getCreatedTime)
                // 违规软删（is_deleted=1）的评论不属于"折叠"：它有自己的复核队列，混进来会出现
                // 两个入口对着同一条内容各做各的处置
                .eq(ApComment::getIsDeleted, 0)
                .orderByDesc(ApComment::getId);
            if (hidden != null) {
                wrapper.eq(ApComment::getIsHidden, hidden);
            }
            IPage<ApComment> result = commentMapper.selectPage(new Page<>(p, s), wrapper);
            total = result.getTotal();
            list = result.getRecords().stream()
                .map(c -> build(AdminContentType.COMMENT, c.getId(), c.getArticleId(), c.getUserId(),
                    c.getUserName(), c.getContent(), c.getIsHidden(), c.getCreatedTime()))
                .collect(Collectors.toList());
        } else {
            var wrapper = new LambdaQueryWrapper<ApPinsComment>()
                .select(ApPinsComment::getId, ApPinsComment::getPinsId, ApPinsComment::getUserId,
                    ApPinsComment::getUserName, ApPinsComment::getContent, ApPinsComment::getIsHidden,
                    ApPinsComment::getCreatedTime)
                .eq(ApPinsComment::getIsDeleted, 0) // 同上：软删行归复核队列管
                .orderByDesc(ApPinsComment::getId);
            if (hidden != null) {
                wrapper.eq(ApPinsComment::getIsHidden, hidden);
            }
            IPage<ApPinsComment> result = pinsCommentMapper.selectPage(new Page<>(p, s), wrapper);
            total = result.getTotal();
            list = result.getRecords().stream()
                .map(c -> build(AdminContentType.PINS_COMMENT, c.getId(), c.getPinsId(), c.getUserId(),
                    c.getUserName(), c.getContent(), c.getIsHidden(), c.getCreatedTime()))
                .collect(Collectors.toList());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult fold(AdminContentType type, Long commentId, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_CONTENT,
            ACTION_FOLD, type.name(), String.valueOf(commentId), reason);
        try {
            return change(type, commentId, true, reason, audit);
        } catch (Exception e) {
            // 失败也留痕：业务事务已标记回滚，这条记录走独立事务（见 AdminAuditRecorder#recordFailure）
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult unfold(AdminContentType type, Long commentId, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_CONTENT,
            ACTION_UNFOLD, type.name(), String.valueOf(commentId), reason);
        try {
            return change(type, commentId, false, reason, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    /**
     * 折叠 / 恢复的共同主体。
     *
     * @param fold true=折叠 false=恢复
     */
    private ResponseResult change(AdminContentType type, Long commentId, boolean fold, String reason,
                                  ApAdminAuditLog audit) {
        int desired = fold ? HIDDEN_YES : HIDDEN_NO;
        // 先确认存在，只为把"记录不存在"和"已经是目标状态"两种 affected=0 区分开；
        // 并发安全仍由后面的条件更新兜底，这个查询不承担闸口职责。
        if (!exists(type, commentId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "评论不存在");
        }

        int affected = conditionalUpdate(type, commentId, desired);
        if (affected == 0) {
            // 走到这里说明记录一定存在，那么 affected=0 只能是在这一瞬间被人改成了目标状态。
            // 明确拒绝而不是当成成功：运营看到"已是折叠状态"会去刷新，看到"操作成功"则会以为
            // 自己刚折叠了一条其实早就被折叠的评论。
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                fold ? "该评论已是折叠状态，请刷新列表" : "该评论未被折叠，无需恢复");
        }

        notifier.notifyCommentAuthor(type.name(), commentId, fold, reason);

        audit.setDetail("hidden: " + (fold ? HIDDEN_NO : HIDDEN_YES) + " -> " + desired);
        auditRecorder.recordSuccess(audit);
        log.info("[AdminFold] 内容{}完成, targetType={}, commentId={}",
            fold ? "折叠" : "恢复", type.name(), commentId);

        Map<String, Object> data = new HashMap<>();
        data.put("hidden", desired);
        return ResponseResult.okResult(data);
    }

    private boolean exists(AdminContentType type, Long commentId) {
        if (commentId == null) {
            return false;
        }
        if (type == AdminContentType.COMMENT) {
            return commentMapper.selectCount(new LambdaQueryWrapper<ApComment>()
                .eq(ApComment::getId, commentId)) > 0;
        }
        return pinsCommentMapper.selectCount(new LambdaQueryWrapper<ApPinsComment>()
            .eq(ApPinsComment::getId, commentId)) > 0;
    }

    /**
     * 条件更新：只在当前状态与目标状态相反时才生效。
     *
     * <p>{@code is_hidden} 两表都是 {@code NOT NULL DEFAULT 0}，不存在 NULL 需要兼容的情况，
     * 所以这里直接等值比较，不必写 {@code OR IS NULL}。
     *
     * @return 影响行数；0 表示记录不存在或已处于目标状态
     */
    private int conditionalUpdate(AdminContentType type, Long commentId, int desired) {
        int from = desired == HIDDEN_YES ? HIDDEN_NO : HIDDEN_YES;
        if (type == AdminContentType.COMMENT) {
            return commentMapper.update(null, new LambdaUpdateWrapper<ApComment>()
                .set(ApComment::getIsHidden, desired)
                .eq(ApComment::getId, commentId)
                .eq(ApComment::getIsHidden, from));
        }
        return pinsCommentMapper.update(null, new LambdaUpdateWrapper<ApPinsComment>()
            .set(ApPinsComment::getIsHidden, desired)
            .eq(ApPinsComment::getId, commentId)
            .eq(ApPinsComment::getIsHidden, from));
    }

    private AdminCommentVO build(AdminContentType type, Long id, Long ownerId, Integer authorId,
                                 String authorName, String content, Integer hidden, Date createdTime) {
        AdminCommentVO vo = new AdminCommentVO();
        vo.setId(id);
        vo.setTargetType(type.name());
        vo.setOwnerId(ownerId);
        vo.setAuthorId(authorId);
        vo.setAuthorName(authorName);
        vo.setContent(content);
        vo.setHidden(hidden);
        vo.setCreatedTime(createdTime);
        return vo;
    }
}
