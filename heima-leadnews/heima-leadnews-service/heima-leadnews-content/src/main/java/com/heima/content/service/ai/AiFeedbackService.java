package com.heima.content.service.ai;

import com.heima.model.ai.pojos.AiFeedback;
import com.heima.model.common.dtos.ResponseResult;

import java.util.List;
import java.util.Map;

/**
 * AI 反馈服务（👍/👎 反馈闭环）
 *
 * <p><b>闭环的后半段</b>：采集（{@link #record}）只是"存了数据"，本接口的
 * {@link #badCases} / {@link #exportEvalCandidates} / {@link #statsByFeature} 才是
 * 让反馈真正改进模型的部分 —— 把 👎 变成可复现的评测样本与可告警的质量指标。
 * 没有这一步，反馈就是死数据（路线图原文："无反馈闭环（商业化灵魂）"）。
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

    /**
     * 导出坏例（👎 样本），按时间倒序 —— 供人工复盘/归因。
     *
     * @param feature 功能筛选（null/空 = 全部功能）
     * @param limit   最多返回条数（1~200）
     */
    List<AiFeedback> badCases(String feature, int limit);

    /**
     * 导出「可直接并入评测集」的候选条目（👎 样本 → eval 集格式）。
     *
     * <p>字段与 {@code ai-eval/eval-questions.json} 对齐：
     * {@code {question, goldenArticleIds: [], note}} —— 其中 goldenArticleIds 留空，
     * 需人工填"期望召回的文章"后追加进评测集（机器无法知道正确答案，但可以把"待补"这一半自动化）。
     */
    List<Map<String, Object>> exportEvalCandidates(String feature, int limit);

    /**
     * 按 feature 统计 👍/👎 与差评率（含阈值告警），供质量监控与迭代决策。
     *
     * @param days 统计天数（1~30）
     */
    Map<String, Object> statsByFeature(int days);
}
