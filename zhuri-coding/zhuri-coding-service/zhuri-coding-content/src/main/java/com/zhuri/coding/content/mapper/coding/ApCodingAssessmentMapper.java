package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAssessment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApCodingAssessmentMapper extends BaseMapper<ApCodingAssessment> {

    /**
     * 最近一次已提交测评（能力档案"测评成绩"块与成绩单回看用）。
     *
     * <p>只取 status=2（已提交）：进行中/已过期的卷无成绩，不参与档案展示。</p>
     *
     * @param userId 用户ID
     * @return 最近一次已提交记录，没有则返回 null
     */
    @Select("SELECT * FROM ap_coding_assessment WHERE user_id = #{userId} AND status = 2 "
        + "ORDER BY submitted_time DESC, id DESC LIMIT 1")
    ApCodingAssessment selectLatestSubmitted(@Param("userId") Integer userId);
}