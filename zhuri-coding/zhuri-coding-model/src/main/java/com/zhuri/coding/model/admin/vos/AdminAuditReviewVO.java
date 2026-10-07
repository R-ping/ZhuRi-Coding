package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 审核复核队列出参（违规任务的人工复核）。
 *
 * <p>队列只出 {@code status=3(违规) 且 review_status=0(未复核)} 的任务；
 * 内容摘要取任务表的提交时快照（评论审核本来就是读快照审的），
 * 违规原因由调度器在违规终态时回填 —— 运营先看"机器为什么判违规"，
 * 再读内容本身下"恢复 / 维持"的判断。
 */
@Data
public class AdminAuditReviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 摘要上限：复核的本质是"人读一遍内容再判断"，120 字只支撑直觉，全文看任务详情 */
    public static final int EXCERPT_MAX_LEN = 120;

    private Long id;

    /** 业务类型编码：article_comment / pins / pins_comment */
    private String bizType;

    /** 业务类型描述：文章评论 / 沸点 / 沸点评论；未知编码原样透出 */
    private String bizTypeDesc;

    /** 业务 ID（评论 ID 或沸点 ID） */
    private Long bizId;

    private Integer authorId;

    private String authorName;

    /** 违规内容摘要（任务表提交时快照，超长截断带省略号） */
    private String contentExcerpt;

    /** 机器判定的违规原因（调度器回填；历史任务可能为 null） */
    private String violationReason;

    /** 关联目标：1-文章 2-沸点（沸点本体任务为 null） */
    private Integer targetType;

    private Long targetId;

    /** 机器审核完成时间（即违规处置时间） */
    private Date auditTime;

    /**
     * 内容当前是否可恢复：软删行在（翻标记）/ 沸点还在审核失败态（翻状态）。
     * false 的常见原因是软删改造<b>之前</b>的历史任务 —— 内容已被物理删除，只能选择"维持违规"。
     */
    private boolean restorable;

    /** 不可恢复的原因说明；可恢复时为 null */
    private String restorableDesc;

    public static String describeBizType(String bizType) {
        if (bizType == null) {
            return null;
        }
        return switch (bizType) {
            case "article_comment" -> "文章评论";
            case "pins" -> "沸点";
            case "pins_comment" -> "沸点评论";
            default -> bizType;
        };
    }

    /** 摘要截断：超长加省略号（与 AIGC 复核队列同一手法） */
    public static String excerpt(String content) {
        if (content == null || content.length() <= EXCERPT_MAX_LEN) {
            return content;
        }
        return content.substring(0, EXCERPT_MAX_LEN) + "…";
    }
}
