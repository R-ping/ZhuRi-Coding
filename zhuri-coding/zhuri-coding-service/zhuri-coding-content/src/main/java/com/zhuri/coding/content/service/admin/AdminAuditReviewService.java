package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营后台 · 审核复核队列（违规任务的人工复核）。
 *
 * <p>这是审核责任链的"人工兜底"第二期：AI 高置信度判违规的内容会被自动处置
 * （评论/沸点评论软删、沸点转审核失败态），误判在此被人工捞回。
 *
 * <p><b>队列来源与 AIGC 复核（{@code AdminAigcReviewService}）不同</b>：
 * 那边出的是"疑似但未处置"的 AIGC 标记记录，这边出的是<b>已被机器处置</b>的
 * 违规任务（{@code ap_audit_task.status=3 且 review_status=0}）——
 * 复核动作是"恢复内容"或"维持处置"，而不是给未处置内容盖章。
 *
 * <p><b>为什么动作叫 restore / uphold 而不是 clear / confirm</b>：
 * AIGC 线的"确认"是"机器疑似成立"，内容不动；本线的"维持"是"机器处置成立"，
 * 内容同样不动。恢复在两类线里语义不同：AIGC 放行是清一个读时判断的标记，
 * 本线恢复是<b>翻转处置事实</b>（软删翻回 / 沸点状态翻回）—— 名称刻意区分开，
 * 免得运营把"机器疑似"与"机器已处置"当成同一类东西。
 *
 * <p>权限复用 {@code AUDIT_REVIEW}（复核 AI 审核）：该权限点的定义就是
 * "AI 判定的人工兜底"，AIGC 线与审核任务线是同一个兜底动作的两条业务线，
 * 拆成两个权限点只会让"谁可以复核"的配置出现两套口径。
 */
public interface AdminAuditReviewService {

    /** 复核放行（恢复内容） */
    String ACTION_RESTORE = "AUDIT_RESTORE";
    /** 维持违规（内容保持不可见，任务退出队列） */
    String ACTION_UPHOLD = "AUDIT_UPHOLD";

    String TARGET_AUDIT_TASK = "AUDIT_TASK";

    int MAX_PAGE_SIZE = 50;
    int DEFAULT_PAGE_SIZE = 20;

    /**
     * 复核队列：违规且未复核的任务，先违规先复核。
     *
     * @param bizType 业务类型过滤（article_comment / pins / pins_comment）；为空表示全部，非法取值报参数错误
     */
    ResponseResult page(String bizType, Integer page, Integer size);

    /**
     * 复核放行：恢复内容可见（软删翻回 / 沸点状态翻回），恢复评论行为记录，
     * 并向内容作者补发"已恢复展示"通知。
     *
     * <p>内容行不存在（软删改造前的历史物理删除）→ 报错不静默成功：那类任务只能选择维持。
     */
    ResponseResult restore(Long id, String reason);

    /** 维持违规：只把任务标记为已复核并退出队列，内容不动、不重复发通知 */
    ResponseResult uphold(Long id, String reason);
}
