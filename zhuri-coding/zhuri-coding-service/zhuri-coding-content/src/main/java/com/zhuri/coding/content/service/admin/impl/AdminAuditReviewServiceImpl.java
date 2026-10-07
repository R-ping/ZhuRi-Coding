package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.audit.ApAuditTaskMapper;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.content.service.admin.AdminAuditReviewService;
import com.zhuri.coding.content.utils.NotificationHelper;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminAuditReviewVO;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 审核复核队列实现。
 *
 * <p>幂等闸口、先读后条件更新、审计分层与 {@code AdminAigcReviewServiceImpl} 同构，
 * 这里只写本线特有的决定：
 *
 * <ul>
 *   <li><b>复核权先占、内容后动</b>：CAS 抢占 review_status 在前，恢复内容在后且同一事务 ——
 *       两个运营同时放行时输家 CAS 命中 0 行报"请刷新后重试"，不会重复恢复、重复发通知；
 *       反过来（先恢复后占权）则双方都会走完整恢复动作，CAS 只挡住一半。</li>
 *   <li><b>恢复动作失败整体失败</b>：内容行不存在（历史物理删除）抛异常并记失败审计，
 *       事务把 review_status 一并回滚 —— 不留"任务显示已放行、内容还删着"的半截态。</li>
 *   <li><b>行为记录恢复是内容恢复的伴随动作</b>：违规时被撤销（status 1→0），放行时置回；
 *       但要防重 —— 用户可能后来又评论过同一目标（已有 status=1 的记录），
 *       只在不存在有效记录时才置回，避免同一目标出现两条有效行为记录污染判重。</li>
 *   <li><b>通知补发不撤回</b>：违规通知已发、站内信无撤回机制；恢复时补发正向通知，
 *       两件事都有痕可查。通知失败只告警（best-effort），不拖垮恢复动作。</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminAuditReviewServiceImpl implements AdminAuditReviewService {

    private static final int REASON_MAX_LEN = 500;
    private static final int DETAIL_MAX_LEN = 1000;

    private static final String BIZ_ARTICLE_COMMENT = ApAuditTask.BIZ_ARTICLE_COMMENT;
    private static final String BIZ_PINS = ApAuditTask.BIZ_PINS;
    private static final String BIZ_PINS_COMMENT = ApAuditTask.BIZ_PINS_COMMENT;

    @Autowired
    private ApAuditTaskMapper auditTaskMapper;

    @Autowired
    private ApCommentMapper apCommentMapper;

    @Autowired
    private ApPinsCommentMapper apPinsCommentMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    // ==================== 队列 ====================

    @Override
    public ResponseResult page(String bizType, Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        if (bizType != null && !bizType.isBlank()) {
            String type = bizType.trim();
            if (!BIZ_ARTICLE_COMMENT.equals(type) && !BIZ_PINS.equals(type) && !BIZ_PINS_COMMENT.equals(type)) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的业务类型：" + type + "（article_comment / pins / pins_comment）");
            }
        }

        LambdaQueryWrapper<ApAuditTask> wrapper = new LambdaQueryWrapper<>();
        // 只出"机器已处置、人还没看过"的任务：这是复核队列的存在意义
        wrapper.eq(ApAuditTask::getStatus, ApAuditTask.STATUS_VIOLATION);
        wrapper.eq(ApAuditTask::getReviewStatus, ApAuditTask.REVIEW_PENDING);
        if (bizType != null && !bizType.isBlank()) {
            wrapper.eq(ApAuditTask::getBizType, bizType.trim());
        }
        // 先违规先复核（audit_time 升序），id 兜底防翻页抖动 —— 与举报队列同一口径
        wrapper.orderByAsc(ApAuditTask::getAuditTime);
        wrapper.orderByAsc(ApAuditTask::getId);

        IPage<ApAuditTask> result = auditTaskMapper.selectPage(new Page<>(p, s), wrapper);
        List<ApAuditTask> records = result.getRecords();
        List<AdminAuditReviewVO> list = records.stream()
            .map(this::toVO)
            .collect(Collectors.toList());
        fillRestorable(list, records);
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    /** 批量回填"当前是否可恢复"：按业务类型分组各查一次（空组不查库），不逐行打库 */
    private void fillRestorable(List<AdminAuditReviewVO> vos, List<ApAuditTask> tasks) {
        if (vos.isEmpty()) {
            return;
        }
        Map<String, AdminAuditReviewVO> byBizTypeAndId = new LinkedHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            byBizTypeAndId.put(tasks.get(i).getBizType() + ":" + tasks.get(i).getBizId(), vos.get(i));
        }

        List<Long> commentIds = bizIdsOf(tasks, BIZ_ARTICLE_COMMENT);
        if (!commentIds.isEmpty()) {
            for (ApComment c : apCommentMapper.selectBatchIds(commentIds)) {
                AdminAuditReviewVO vo = byBizTypeAndId.get(BIZ_ARTICLE_COMMENT + ":" + c.getId());
                if (vo != null) {
                    applyCommentRestorable(vo, c.getIsDeleted());
                }
            }
            markMissingUnrestorable(byBizTypeAndId, commentIds, BIZ_ARTICLE_COMMENT,
                "内容已不存在（软删改造前的历史物理删除），只能选择维持违规");
        }

        List<Long> pinsCommentIds = bizIdsOf(tasks, BIZ_PINS_COMMENT);
        if (!pinsCommentIds.isEmpty()) {
            for (ApPinsComment c : apPinsCommentMapper.selectBatchIds(pinsCommentIds)) {
                AdminAuditReviewVO vo = byBizTypeAndId.get(BIZ_PINS_COMMENT + ":" + c.getId());
                if (vo != null) {
                    applyCommentRestorable(vo, c.getIsDeleted());
                }
            }
            markMissingUnrestorable(byBizTypeAndId, pinsCommentIds, BIZ_PINS_COMMENT,
                "内容已不存在（软删改造前的历史物理删除），只能选择维持违规");
        }

        List<Long> pinsIds = bizIdsOf(tasks, BIZ_PINS);
        if (!pinsIds.isEmpty()) {
            for (ApPins p : apPinsMapper.selectBatchIds(pinsIds)) {
                AdminAuditReviewVO vo = byBizTypeAndId.get(BIZ_PINS + ":" + p.getId());
                if (vo != null) {
                    applyPinsRestorable(vo, p.getStatus());
                }
            }
            markMissingUnrestorable(byBizTypeAndId, pinsIds, BIZ_PINS, "沸点已不存在，只能选择维持违规");
        }
    }

    private void applyCommentRestorable(AdminAuditReviewVO vo, Integer isDeleted) {
        if (isDeleted != null && isDeleted == 1) {
            vo.setRestorable(true);
        } else {
            vo.setRestorable(false);
            vo.setRestorableDesc("内容当前不处于违规删除状态，无需恢复");
        }
    }

    private void applyPinsRestorable(AdminAuditReviewVO vo, Byte status) {
        if (status != null && status == ApPins.Status.FAIL.getCode()) {
            vo.setRestorable(true);
        } else {
            vo.setRestorable(false);
            vo.setRestorableDesc("沸点当前不处于审核失败状态，无需恢复");
        }
    }

    private void markMissingUnrestorable(Map<String, AdminAuditReviewVO> index,
                                         List<Long> ids, String bizType, String desc) {
        for (Long id : ids) {
            AdminAuditReviewVO vo = index.get(bizType + ":" + id);
            // 回查结果里没有 = 行已不在（历史物理删除）；未被上面两个 apply 碰过的才是"行不在"
            if (vo != null && !vo.isRestorable() && vo.getRestorableDesc() == null) {
                vo.setRestorableDesc(desc);
            }
        }
    }

    private List<Long> bizIdsOf(List<ApAuditTask> tasks, String bizType) {
        return tasks.stream()
            .filter(t -> bizType.equals(t.getBizType()))
            .map(ApAuditTask::getBizId)
            .collect(Collectors.toList());
    }

    // ==================== 复核放行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult restore(Long id, String reason) {
        return doReview(id, reason, ApAuditTask.REVIEW_RESTORED);
    }

    // ==================== 维持违规 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult uphold(Long id, String reason) {
        return doReview(id, reason, ApAuditTask.REVIEW_UPHELD);
    }

    /** 复核共用主体：先读（给人话错误）→ CAS 抢复核权（挡并发）→ 按目标状态分派恢复动作 */
    private ResponseResult doReview(Long id, String reason, int targetReviewStatus) {
        boolean restoring = targetReviewStatus == ApAuditTask.REVIEW_RESTORED;
        String action = restoring ? ACTION_RESTORE : ACTION_UPHOLD;
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "任务ID不合法");
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_CONTENT,
            action, TARGET_AUDIT_TASK, String.valueOf(id), reason.trim());
        try {
            ApAuditTask task = auditTaskMapper.selectById(id);
            if (task == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "任务不存在");
            }
            if (!Objects.equals(task.getStatus(), ApAuditTask.STATUS_VIOLATION)) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "只有违规任务可以复核（当前状态：" + task.getStatus() + "）");
            }
            if (task.getReviewStatus() != null && task.getReviewStatus() != ApAuditTask.REVIEW_PENDING) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "该任务已复核（" + reviewDesc(task.getReviewStatus()) + "），无需重复处理");
            }

            // 先占复核权再动内容：输家 CAS 命中 0 行报刷新重试，不会双恢复、双通知
            int rows = auditTaskMapper.update(null, new LambdaUpdateWrapper<ApAuditTask>()
                .set(ApAuditTask::getReviewStatus, targetReviewStatus)
                .set(ApAuditTask::getReviewTime, new Date())
                .eq(ApAuditTask::getId, id)
                .eq(ApAuditTask::getStatus, ApAuditTask.STATUS_VIOLATION)
                .eq(ApAuditTask::getReviewStatus, ApAuditTask.REVIEW_PENDING));
            if (rows == 0) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "复核状态已被其他操作改变，请刷新后重试");
            }

            if (restoring) {
                String contentDesc = restoreContent(task);
                audit.setDetail(truncate("复核放行：" + contentDesc
                    + (task.getViolationReason() == null ? "" : "；机器判定原因=" + task.getViolationReason())));
            } else {
                audit.setDetail(truncate("维持违规：内容保持不可见"
                    + (task.getViolationReason() == null ? "" : "；机器判定原因=" + task.getViolationReason())));
            }
            auditRecorder.recordSuccess(audit);
            log.info("[AdminAuditReview] {} taskId={}, bizType={}, bizId={}",
                action, id, task.getBizType(), task.getBizId());
            return ResponseResult.okResult();
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    /**
     * 按业务类型恢复内容（同一事务：CAS 复核权已占，这里失败抛异常整体回滚）。
     *
     * @return 审计摘要（恢复动作描述）
     */
    private String restoreContent(ApAuditTask task) {
        return switch (task.getBizType() == null ? "" : task.getBizType()) {
            case BIZ_ARTICLE_COMMENT -> restoreArticleComment(task);
            case BIZ_PINS_COMMENT -> restorePinsComment(task);
            case BIZ_PINS -> restorePins(task);
            default -> throw new IllegalStateException("无法识别的业务类型：" + task.getBizType()
                + "（坏数据不能伪装成恢复成功）");
        };
    }

    /** 文章评论恢复（独立实现，供 switch 调用） */
    private String restoreArticleComment(ApAuditTask task) {
        ApComment row = apCommentMapper.selectById(task.getBizId());
        if (row == null) {
            throw new IllegalStateException("评论已不存在（软删改造前的历史物理删除），无法恢复，请选择维持违规");
        }
        if (row.getIsDeleted() == null || row.getIsDeleted() != 1) {
            throw new IllegalStateException("评论当前不处于违规删除状态，无需恢复");
        }
        int rows = apCommentMapper.update(null, new LambdaUpdateWrapper<ApComment>()
            .set(ApComment::getIsDeleted, 0)
            .eq(ApComment::getId, task.getBizId())
            .eq(ApComment::getIsDeleted, 1));
        if (rows == 0) {
            throw new IllegalStateException("恢复状态已被其他操作改变，请刷新后重试");
        }
        restoreBehaviorRecord(task.getAuthorId(),
            Objects.equals(task.getTargetType(), 1) ? "comment_article" : "comment_pin",
            task.getTargetId());
        NotificationHelper.sendReviewRestoreNotification(
            notificationClient, task.getAuthorId() == null ? null : task.getAuthorId().longValue(),
            "评论", row.getContent());
        return "评论(" + task.getBizId() + ")已恢复可见";
    }

    /** 沸点评论恢复（独立实现，供 switch 调用） */
    private String restorePinsComment(ApAuditTask task) {
        ApPinsComment row = apPinsCommentMapper.selectById(task.getBizId());
        if (row == null) {
            throw new IllegalStateException("沸点评论已不存在（软删改造前的历史物理删除），无法恢复，请选择维持违规");
        }
        if (row.getIsDeleted() == null || row.getIsDeleted() != 1) {
            throw new IllegalStateException("沸点评论当前不处于违规删除状态，无需恢复");
        }
        int rows = apPinsCommentMapper.update(null, new LambdaUpdateWrapper<ApPinsComment>()
            .set(ApPinsComment::getIsDeleted, 0)
            .eq(ApPinsComment::getId, task.getBizId())
            .eq(ApPinsComment::getIsDeleted, 1));
        if (rows == 0) {
            throw new IllegalStateException("恢复状态已被其他操作改变，请刷新后重试");
        }
        restoreBehaviorRecord(task.getAuthorId(), "comment_pin", task.getTargetId());
        NotificationHelper.sendReviewRestoreNotification(
            notificationClient, task.getAuthorId() == null ? null : task.getAuthorId().longValue(),
            "沸点评论", row.getContent());
        return "沸点评论(" + task.getBizId() + ")已恢复可见";
    }

    /** 沸点本体恢复：审核失败态（FAIL）翻回已发布（PUBLISHED） */
    private String restorePins(ApAuditTask task) {
        ApPins row = apPinsMapper.selectById(task.getBizId());
        if (row == null) {
            throw new IllegalStateException("沸点已不存在，无法恢复，请选择维持违规");
        }
        if (row.getStatus() == null || row.getStatus() != ApPins.Status.FAIL.getCode()) {
            throw new IllegalStateException("沸点当前不处于审核失败状态（status=" + row.getStatus() + "），无需恢复");
        }
        int rows = apPinsMapper.update(null, new LambdaUpdateWrapper<ApPins>()
            .set(ApPins::getStatus, ApPins.Status.PUBLISHED.getCode())
            .set(ApPins::getReviewTime, new Date())
            .eq(ApPins::getId, task.getBizId())
            .eq(ApPins::getStatus, ApPins.Status.FAIL.getCode()));
        if (rows == 0) {
            throw new IllegalStateException("恢复状态已被其他操作改变，请刷新后重试");
        }
        NotificationHelper.sendReviewRestoreNotification(
            notificationClient, row.getAuthorId(), "沸点", row.getContent());
        return "沸点(" + task.getBizId() + ")已恢复为已发布";
    }

    /**
     * 恢复违规时被撤销的行为记录（best-effort：失败只告警，不回滚内容恢复 ——
     * 行为记录影响的是积分侧判重，内容可见性是主事实）。
     *
     * <p>防重：用户可能在违规后又评论过同一目标（已有 status=1 记录），
     * 只在不存在有效记录时才置回，避免同一目标出现两条有效记录污染行为判重。
     */
    private void restoreBehaviorRecord(Integer userId, String behaviorType, Long targetId) {
        if (userId == null || targetId == null) {
            return;
        }
        try {
            Long active = behaviorRecordMapper.selectCount(new LambdaQueryWrapper<UserBehaviorRecord>()
                .eq(UserBehaviorRecord::getUserId, userId)
                .eq(UserBehaviorRecord::getBehaviorType, behaviorType)
                .eq(UserBehaviorRecord::getTargetId, targetId)
                .eq(UserBehaviorRecord::getStatus, 1));
            if (active != null && active > 0) {
                return; // 已有有效记录（用户后来又评论过），不重复置回
            }
            behaviorRecordMapper.update(null, new LambdaUpdateWrapper<UserBehaviorRecord>()
                .set(UserBehaviorRecord::getStatus, 1)
                .eq(UserBehaviorRecord::getUserId, userId)
                .eq(UserBehaviorRecord::getBehaviorType, behaviorType)
                .eq(UserBehaviorRecord::getTargetId, targetId)
                .eq(UserBehaviorRecord::getStatus, 0));
        } catch (Exception e) {
            log.warn("恢复评论行为记录失败（内容恢复不受影响）, userId={}, targetId={}", userId, targetId, e);
        }
    }

    // ==================== 出参装配与小工具 ====================

    private AdminAuditReviewVO toVO(ApAuditTask task) {
        AdminAuditReviewVO vo = new AdminAuditReviewVO();
        vo.setId(task.getId());
        vo.setBizType(task.getBizType());
        vo.setBizTypeDesc(AdminAuditReviewVO.describeBizType(task.getBizType()));
        vo.setBizId(task.getBizId());
        vo.setAuthorId(task.getAuthorId());
        vo.setAuthorName(task.getAuthorName());
        vo.setContentExcerpt(AdminAuditReviewVO.excerpt(task.getContent()));
        vo.setViolationReason(task.getViolationReason());
        vo.setTargetType(task.getTargetType());
        vo.setTargetId(task.getTargetId());
        vo.setAuditTime(task.getAuditTime());
        // restorable 由 fillRestorable 批量回填；缺省 false（宁可让运营看到"不能恢复"，不可虚报可恢复）
        vo.setRestorable(false);
        return vo;
    }

    private static String reviewDesc(int reviewStatus) {
        return switch (reviewStatus) {
            case ApAuditTask.REVIEW_RESTORED -> "复核放行";
            case ApAuditTask.REVIEW_UPHELD -> "维持违规";
            default -> "未复核";
        };
    }

    private static ResponseResult validateReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (value.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }

    private static int normalizePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    private static int normalizeSize(Integer size) {
        return (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;
    }

    private static Map<String, Object> pageData(List<?> list, long total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= DETAIL_MAX_LEN) {
            return text;
        }
        return text.substring(0, DETAIL_MAX_LEN);
    }
}
