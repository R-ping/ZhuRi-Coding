package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营后台 · AIGC 复核队列。
 *
 * <p><b>这个队列在补什么洞</b>：AIGC 检测的处置是"只标不删"——flagged 后关打赏、
 * 禁售、排除出 RAG 向量库，但作者几乎感知不到（内容还在、只是变现闸门被静默关掉），
 * 所以不会有人来申诉；而申诉终审（{@code APPEAL_REVIEW}）是唯一的恢复通道，
 * 等于恢复通道永远没人走。被误伤的内容需要一个<b>主动巡检</b>的入口：
 * 运营按疑似分从高到低过一遍，逐条放行或确认。这就是本服务。
 *
 * <p><b>两个动作，两种影响面</b>：
 * <ul>
 *   <li><b>放行（{@link #clear}）</b>：记录转"复核放行"，业务主表清标记
 *       （{@code is_aigc=0, aigc_score=0}，与申诉终审 {@code revertDisposal} 同一口径）。
 *       所有下游处置都是读时判断 {@code is_aigc}，清标记即自动恢复，无需逐个通知；</li>
 *   <li><b>确认（{@link #confirm}）</b>：记录转"人工确认"，业务标记<b>保留</b>。
 *       它不是处置动作（处置在 flagged 时已经发生），而是把"机器疑似"钉死成
 *       "人工确认"，让这条记录退出队列、也不再被后续的特征迭代反复改判。</li>
 * </ul>
 *
 * <p><b>为什么第一期只做 AIGC 线，审核任务线（评论/沸点违规复核）不做</b>：
 * AIGC 处置完全可逆（清标记即恢复）；而评论违规走 {@code handleFailed} 是<b>物理删除</b>，
 * 复核只能靠任务表快照重建内容，但快照里没有 {@code root_id/parent_id}（回复关系丢失），
 * 且违规通知已发无法撤回——"复核通过"根本恢复不回原状。要做那条线，得先给任务表
 * 补回复关系快照、把物理删除改成软删除，属于独立的一期改造，不混在这里。
 *
 * <p><b>幂等闸口</b>：两个动作都按<b>状态比对</b>落地——
 * {@code UPDATE ... SET status=? WHERE id=? AND status=1(已标记)}。
 * 记录已经是目标状态时明确报错（"无需复核"），而不是静默返回成功；
 * 条件更新命中 0 行说明被并发复核过，回一句"请刷新后重试"。
 */
public interface AdminAigcReviewService {

    /** 审计动作：复核放行（清 AI 标记，内容恢复变现/入库资格） */
    String ACTION_CLEAR = "AIGC_CLEAR";

    /** 审计动作：人工确认 AI 水文（保留标记，退出队列） */
    String ACTION_CONFIRM = "AIGC_CONFIRM";

    /** 审计对象类型：AIGC 检测记录（{@code ap_aigc_record} 行） */
    String TARGET_AIGC_RECORD = "AIGC_RECORD";

    /** 列表单页上限（与 {@code AdminActivityService} 同一口径，拦住误传的 size=100000） */
    int MAX_PAGE_SIZE = 50;

    /** 列表默认每页条数 */
    int DEFAULT_PAGE_SIZE = 20;

    /**
     * 复核队列：已 flagged 的检测记录，按疑似分降序。
     *
     * <p>只出 {@code status=1}（已标记）——其余状态要么还没处置（仅记录，不用人捞），
     * 要么已经出队（申诉中/放行/确认）。排序用疑似分降序：分数越高越可能是真水文，
     * 优先看高分的能最快确认"检测没疯"；低分段的误伤反而更少、更不急。
     */
    ResponseResult page(Integer page, Integer size);

    /**
     * 复核放行：记录转"复核放行"，业务主表清 AI 标记。
     *
     * @param id     检测记录ID（{@code ap_aigc_record.id}）
     * @param reason 操作理由（必填）
     */
    ResponseResult clear(Long id, String reason);

    /**
     * 人工确认 AI 水文：记录转"人工确认"，业务标记保留。
     *
     * @param id     检测记录ID
     * @param reason 操作理由（必填）
     */
    ResponseResult confirm(Long id, String reason);
}
