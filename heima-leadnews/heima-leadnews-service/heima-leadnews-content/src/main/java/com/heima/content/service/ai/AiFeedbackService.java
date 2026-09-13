package com.heima.content.service.ai;

import com.heima.model.common.dtos.ResponseResult;

/**
 * AI 反馈服务（👍/👎 反馈闭环）
 */
public interface AiFeedbackService {

    /**
     * 记录反馈（幂等：同 user+feature+scene+question 唯一，重复反馈更新原值）
     *
     * @param feature  AI 功能标识（见 AiFeedback.FEATURE_*）
     * @param sceneId  场景ID（如文章ID字符串，可为空串）
     * @param question 用户问题
     * @param answer   模型回答（截断留档）
     * @param feedback 1=有帮助 -1=没帮助/有误
     */
    ResponseResult record(Integer userId, String feature, String sceneId,
                          String question, String answer, Integer feedback);
}
