package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.coding.dtos.CodingInterviewFinishDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewStartDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewTurnDTO;
import com.zhuri.coding.model.coding.vos.CodingInterviewTurnVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 模拟面试服务（Coding 延展第三层 · Stage A）
 *
 * <p>单人单场生命周期：1进行中 → 2已完成 / 3已过期。开面时一次生成提纲（含 keyPoints 关键考点，
 * 仅服务端可见）；每轮作答经 SSE 流式返回追问或下一题（首行控制行 FOLLOWUP/NEXT 协议 +
 * 服务端强制追问上限）；结束后生成报告（三维等级 + 覆盖考点清单 + 总评建议，解析失败存原文可重试）。</p>
 *
 * <p>额度口径：开面做一次准入预检（免费 token 优先、钱包兜底）；每轮/报告由
 * {@code AiLlmGateway} 按实际 token 自动结算；流式耗尽经 {@code QuotaExhaustedException} 上抛，
 * 由控制器转为 {@code [3301]} 错误事件。</p>
 */
public interface CodingInterviewService {

    /**
     * 开面：进行中未超时直接续答（deadline 不变）；否则每日场次校验 → 额度预检 →
     * 提纲生成（同步 LLM，不合格拒绝且不落库）→ 落库，返回首题与截止时间。
     */
    ResponseResult start(Integer userId, CodingInterviewStartDTO dto);

    /**
     * 进行中的面试（懒过期：已超时置过期并返回空）
     */
    ResponseResult current(Integer userId);

    /**
     * 提交作答（SSE 驱动）：流式回调面试官发言（追问/下一题），结束时回调进度；
     * turnSeq 防重（与 DB turn_count 不一致拒绝）；追问上限服务端强制。
     */
    void turnStream(Integer userId, CodingInterviewTurnDTO dto, TurnSink sink);

    /**
     * 结束面试：幂等（已结束且报告可用直接回放；报告缺失/不可解析则重新生成）；
     * 超时拒绝（到点即废，与测评一致）。
     */
    ResponseResult finish(Integer userId, CodingInterviewFinishDTO dto);

    /**
     * 面试报告 + 全量回放（仅本人）
     */
    ResponseResult report(Integer userId, Long id);

    /**
     * 已完成场次历史（分页，按开面时间倒序）
     */
    ResponseResult history(Integer userId, Integer page, Integer size);

    /**
     * 轮次流式回调（控制器接 SSE；实现侧负责把 LLM 增量转发为 delta）。
     *
     * <p><b>取消语义</b>：delta 实现可在客户端断开时抛 {@code CancellationException}，
     * 该异常将穿透 LLM 流并中断生成（省 token）；本轮不落库，用户刷新后可重试。</p>
     */
    interface TurnSink {

        /** 面试官发言增量（打字机效果） */
        void delta(String text);

        /** 本轮完成：完整发言 + 进度（completed=true 时前端自动调 finish） */
        void done(CodingInterviewTurnVO vo);

        /** 业务错误（额度耗尽由 {@code QuotaExhaustedException} 单独上抛，不走此处） */
        void error(String message);
    }
}