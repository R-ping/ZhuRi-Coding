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
     * <p>{@code status = 1} 是必须的：抽题时会先落一条「已发放未作答」的流水，
     * 只看了题没作答不该算作活跃月。</p>
     *
     * @param userId 用户ID
     * @return 有作答记录的自然月数（无记录为 0）
     */
    @Select("SELECT COUNT(DISTINCT DATE_FORMAT(answer_date, '%Y-%m')) FROM ap_coding_answer_record "
        + "WHERE user_id = #{userId} AND status = 1")
    int countActiveMonths(@Param("userId") Integer userId);

    /**
     * 平均等级（相对用户自己的历史，不是相对全站）。
     *
     * <p>排除 {@code level IS NULL}（未评估的作答）：没评出来不该被当成 0 分拉低均值 ——
     * 与报告里「未评估占位不参与综合等级」是同一条口径。没有任何评估结果时返回 null。</p>
     *
     * <p>另加 {@code status = 1}：未作答的流水 level 本就是 NULL，这条其实筛不掉更多的行，
     * 但把它写出来，读的人不必再去确认"未作答会不会混进均值"。</p>
     */
    @Select("SELECT AVG(level) FROM ap_coding_answer_record "
        + "WHERE user_id = #{userId} AND level IS NOT NULL AND status = 1")
    Double avgLevel(@Param("userId") Integer userId);
}