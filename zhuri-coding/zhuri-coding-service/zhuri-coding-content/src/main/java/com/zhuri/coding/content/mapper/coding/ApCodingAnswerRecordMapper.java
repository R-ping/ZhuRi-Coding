package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApCodingAnswerRecordMapper extends BaseMapper<ApCodingAnswerRecord> {

    /**
     * 榜单聚合（当日/本周/本月）：只统计"当日一题"，自由练习不计入。
     *
     * <p>排序口径（PRD 第一层）：答对题数优先 → 正确率 → 平均用时；
     * 用时可能缺失（前端未上报），用 IFNULL 兜底到极大值排同分末尾。</p>
     *
     * @param startDate 统计起点（含）
     * @param limit     榜单条数
     * @return 每行：userId / totalCount / correctCount / avgSeconds
     */
    @Select("SELECT user_id AS userId, COUNT(*) AS totalCount, SUM(is_correct) AS correctCount, "
        + "IFNULL(AVG(elapsed_seconds), 999999) AS avgSeconds "
        + "FROM ap_coding_answer_record "
        + "WHERE is_daily = 1 AND answer_date >= #{startDate} "
        + "GROUP BY user_id "
        + "ORDER BY correctCount DESC, (SUM(is_correct) / COUNT(*)) DESC, avgSeconds ASC "
        + "LIMIT #{limit}")
    List<Map<String, Object>> selectRanking(@Param("startDate") Date startDate, @Param("limit") int limit);
}