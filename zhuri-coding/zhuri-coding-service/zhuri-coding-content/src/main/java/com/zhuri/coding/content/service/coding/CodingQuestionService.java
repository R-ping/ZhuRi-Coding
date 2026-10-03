package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.coding.dtos.CodingAnswerDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 每日一题与刷题服务（Coding 延展第一层）
 *
 * <p>判题只支持单选/多选（判题三步走第一步）；当日一题一天一次、答后锁定，
 * 答对计分（逐力值等级）并触发签到打卡（连续天数与签到共用一份记录）。</p>
 */
public interface CodingQuestionService {

    /**
     * 今日题目：当天已答则回放完整作答结果；未答则取缓存题目（难度可按历史正确率自适应或自选）。
     *
     * @param userId 登录用户ID
     * @param difficulty 自选难度（可为 null，null 走自适应）
     */
    ResponseResult today(Integer userId, Integer difficulty);

    /**
     * 提交作答：判分 → 落库（记录/题目计数/用户统计）→ 当日一题答对时计等级分并打卡。
     */
    ResponseResult answer(Integer userId, CodingAnswerDTO dto);

    /**
     * 榜单（day/week/month）：只统计当日一题，按答对题数 → 正确率 → 平均用时排序。
     *
     * @param currentUserId 当前登录用户（可为 null，用于标记 isSelf）
     */
    ResponseResult ranking(String period, Integer currentUserId);

    /**
     * 题库列表（练习）：分页返回上架题目，不含答案；登录用户标记已答。
     *
     * @param articleId 按来源文章过滤（可为 null；文章详情页"相关练习"反向入口用）
     * @param userId 当前登录用户（可为 null，匿名浏览）
     */
    ResponseResult questions(Integer difficulty, Long articleId, Integer page, Integer size, Integer userId);

    /**
     * 我的编码统计：连续天数（签到体系）+ 作答总数/正确率/领域分布 + 今日作答态。
     */
    ResponseResult myStat(Integer userId);
}