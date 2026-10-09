package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApCodingAnswerRecordMapper extends BaseMapper<ApCodingAnswerRecord> {

    /**
     * 活跃月份数（能力档案"持续度"块）：作答题去重到"年-月"后计数，反映坚持跨度而非爆发。
     *
     * <p>每日一题与自由练习合并计算——练习同样是活跃信号；走 idx_user_date 覆盖。</p>
     *
     * @param userId 用户ID
     * @return 有作答记录的自然月数（无记录为 0）
     */
    @Select("SELECT COUNT(DISTINCT DATE_FORMAT(answer_date, '%Y-%m')) FROM ap_coding_answer_record "
        + "WHERE user_id = #{userId}")
    int countActiveMonths(@Param("userId") Integer userId);

    /**
     * 平均等级（相对用户自己的历史，不是相对全站）。
     *
     * <p>排除 {@code level IS NULL}（未评估的作答）：没评出来不该被当成 0 分拉低均值 ——
     * 与报告里「未评估占位不参与综合等级」是同一条口径。没有任何评估结果时返回 null。</p>
     */
    @Select("SELECT AVG(level) FROM ap_coding_answer_record "
        + "WHERE user_id = #{userId} AND level IS NOT NULL")
    Double avgLevel(@Param("userId") Integer userId);
}