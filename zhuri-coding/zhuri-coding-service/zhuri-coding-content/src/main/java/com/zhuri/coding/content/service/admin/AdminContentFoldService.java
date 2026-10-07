package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.admin.AdminContentType;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营侧「内容折叠」。
 *
 * <p><b>为什么折叠要有一个运营入口</b>：折叠目前只有 AI 一侧的写入
 * （{@code CommentAuditService} / {@code PinsCommentAuditService} 判"引战/阴阳/软广"后置
 * {@code is_hidden=1}）。也就是说，全文只有"折"没有"解"—— 判错了、误伤了，
 * 除了评论者自己发起申诉（{@code ap_content_appeal}）之外没有任何纠正手段，
 * 而申诉链路对运营是"先有申诉才有复核"，无申诉的误伤就一直挂着。
 * 这个入口补齐运营侧的「折」与「解」两个方向。
 *
 * <p><b>折叠与下架的分工</b>：折叠是温和处置 —— 内容还在，只是列表不展示（或仅作者可见），
 * 可逆、不需要解释索引与向量；下架是不可逆的线上动作，要同步清 ES 与向量。
 * 两者摆在运营面前时，**默认应该是折叠**，下架要能说出为什么折叠不够。
 *
 * <p><b>为什么要通知评论者</b>：折叠对作者是"内容还在但没人看得见"——不说一声，
 * 作者只会以为自己的评论被吞了，既不会改也不会申诉。通知里带上折叠理由，
 * 是这条链路唯一能让作者知道"为什么"的地方。
 */
public interface AdminContentFoldService {

    /** 审计动作：折叠 */
    String ACTION_FOLD = "CONTENT_FOLD";
    /** 审计动作：解除折叠 */
    String ACTION_UNFOLD = "CONTENT_UNFOLD";

    /**
     * 折叠列表分页。
     *
     * @param type   内容类型（必填）
     * @param hidden 0 正常 / 1 已折叠；null 表示不过滤
     */
    ResponseResult page(AdminContentType type, Integer hidden, Integer page, Integer size);

    /**
     * 折叠一条评论（重复折叠会被拒绝，不会静默成功）。
     *
     * @param type      内容类型
     * @param commentId 评论ID
     * @param reason    折叠理由（必填，写审计并原样告知评论者）
     */
    ResponseResult fold(AdminContentType type, Long commentId, String reason);

    /**
     * 解除折叠（运营复核认为 AI 误伤时使用）。
     *
     * @param type      内容类型
     * @param commentId 评论ID
     * @param reason    恢复理由（必填）
     */
    ResponseResult unfold(AdminContentType type, Long commentId, String reason);
}
