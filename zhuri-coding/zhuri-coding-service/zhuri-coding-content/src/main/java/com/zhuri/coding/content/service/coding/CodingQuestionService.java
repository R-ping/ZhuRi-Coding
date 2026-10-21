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
     * 今日题目：当天已答则回放完整结果（含等级与考点清单）；否则按方向抽一道题返回。
     *
     * <p><b>抽题不落库</b>：这是纯读操作。用户抽到不想做的，再调一次就是换一道，
     * 换多少次都不产生流水 —— 只有 {@link #startAnswer} 才落「未作答」记录。</p>
     *
     * <p>抽题方向：显式传入优先，其次取用户上次设定的方向，都没有则用默认方向。
     * 难度不由用户选 —— 池子里每道题自带难度，避免多一个无意义的交互。</p>
     *
     * @param userId    登录用户ID
     * @param direction 自选方向（可为 null）
     * @param refresh   是否换一道（true = 即使已经开始作答了也重新抽）
     */
    ResponseResult today(Integer userId, String direction, Boolean refresh);

    /**
     * 开始作答：落一条 {@code status = 未作答} 的流水，把「他正在做的是哪道」记下来。
     *
     * <p>用户中途退出或关掉页面，这条记录就停在未作答，每日作答历史里能按状态浏览。
     * 同一天只有一行 —— 换题重新开始时，改的是这行的指向。</p>
     *
     * @param poolId 用户抽中、决定要做的题目
     */
    ResponseResult startAnswer(Integer userId, Long poolId);

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
