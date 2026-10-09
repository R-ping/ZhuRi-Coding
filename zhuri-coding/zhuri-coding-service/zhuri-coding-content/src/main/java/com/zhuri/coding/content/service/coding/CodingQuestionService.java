package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.coding.dtos.CodingDailyAnswerDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 每日一题服务（Coding 延展第一层 · 简答）
 *
 * <p><b>题目来自人工维护的题目池</b>（{@code ap_coding_daily_pool}），不由模型现生成：
 * 简答题没有唯一答案，评估必须有可信的评分锚点（关键考点），
 * 让模型既出题又判分等于"自己出题自己批"。</p>
 *
 * <p>一天一次、答后锁定；完成即记逐日分（没有对错，所以不按对错给分）。
 * 签到是独立入口，答题不代打卡。</p>
 */
public interface CodingQuestionService {

    /**
     * 今日题目：当天已答则回放完整结果（含等级与考点清单）；未答则按方向抽题。
     *
     * <p>抽题方向：显式传入优先，其次取用户上次设定的方向，都没有则用默认方向。
     * 难度不由用户选 —— 池子里每道题自带难度，避免多一个无意义的交互。</p>
     *
     * @param userId    登录用户ID
     * @param direction 自选方向（可为 null）
     */
    ResponseResult today(Integer userId, String direction);

    /**
     * 提交作答：评估 → 落库（流水 + 统计）→ 记逐日分。
     *
     * <p>评估降级（模型不可用）时等级为空，但作答照常落库 —— 用户写的东西不该丢。</p>
     */
    ResponseResult answer(Integer userId, CodingDailyAnswerDTO dto);

    /**
     * 我的编码统计：连续签到天数 + 累计作答/平均等级/领域分布 + 今日状态 + 当前方向。
     */
    ResponseResult myStat(Integer userId);
}
