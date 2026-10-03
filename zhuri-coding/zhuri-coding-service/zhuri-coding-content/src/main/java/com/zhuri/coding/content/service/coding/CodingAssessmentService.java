package com.zhuri.coding.content.service.coding;

import com.zhuri.coding.model.coding.dtos.CodingAssessmentSubmitDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 能力测评服务（Coding 延展第二层 · Stage B）
 *
 * <p>单人单卷生命周期：开卷（组卷快照）→ 进行中（可续答，deadline 不变）→ 已提交 / 已过期。
 * 冷却（重考间隔）只约束"已提交"；进行中/已过期可立即重开。判分以组卷快照为准，
 * 题库后续变更不影响历史成绩。</p>
 */
public interface CodingAssessmentService {

    /**
     * 开卷：冷却内拒绝（提示下次可考时间）；有进行中返回续答；否则组卷创建
     */
    ResponseResult start(Integer userId);

    /**
     * 进行中的测评（懒过期：已超时置过期并返回空）
     */
    ResponseResult current(Integer userId);

    /**
     * 交卷判分：幂等（重复交卷返回同一成绩单）；超时拒绝
     */
    ResponseResult submit(Integer userId, CodingAssessmentSubmitDTO dto);

    /**
     * 最近一次已提交成绩单（无记录返回空）
     */
    ResponseResult latest(Integer userId);

    /**
     * 历史列表（分页，含进行中/已过期状态）
     */
    ResponseResult history(Integer userId, Integer page, Integer size);
}