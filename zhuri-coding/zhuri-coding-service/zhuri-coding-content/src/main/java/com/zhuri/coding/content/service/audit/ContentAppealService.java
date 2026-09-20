package com.heima.content.service.audit;

import com.heima.model.common.dtos.ResponseResult;

import java.util.Map;

/**
 * 内容治理申诉服务（AI 预审 + 人工终审）
 *
 * <p>让治理误伤可纠正：评论折叠 / 文章 AIGC 误标的归属者可发起申诉；
 * AI 预审只产出建议（suggest/score/reason），终审由人工执行。
 */
public interface ContentAppealService {

    /**
     * 提交申诉（归属校验 + 幂等：同对象存在处理中申诉则拒绝）
     *
     * @return data = {appealId, status}
     */
    ResponseResult submit(Integer appealType, Long contentId, String reason, Integer applicantId);

    /**
     * 人工终审：allow-解除处置 / uphold-维持（禁止申诉人自审）
     *
     * @return data = {appealId, status}
     */
    ResponseResult review(Long appealId, String action, Integer reviewerId);

    /**
     * 申诉状态查询（本人）：返回 {status, aiVerdict{...}, reason, createTime}
     */
    ResponseResult getStatus(Integer appealType, Long contentId, Integer userId);
}
