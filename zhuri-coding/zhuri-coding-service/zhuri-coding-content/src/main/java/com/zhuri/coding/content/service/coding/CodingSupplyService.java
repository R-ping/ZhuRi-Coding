package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.coding.dtos.CodingQuestionSubmitDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 题库供给服务（Coding 延展第一层 · 两条不依赖外采的来源）
 *
 * <p>来源一：AI 从已发布文章反向生成题目（作者为自己的文章触发，题目与文章互相导流）；
 * 来源二：作者投稿（格式校验 + 题干查重 + 一次 AI 质检，通过即上架，驳回带原因）。</p>
 */
public interface CodingSupplyService {

    /**
     * 从文章生成题目并入库（仅文章作者可触发，每日有次数上限）。
     */
    ResponseResult generateFromArticle(Integer userId, Long articleId);

    /**
     * 作者投稿题目：格式校验 → 题干查重 → AI 质检 → 上架/驳回。
     */
    ResponseResult submitQuestion(Integer userId, CodingQuestionSubmitDTO dto);
}