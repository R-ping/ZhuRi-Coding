package com.zhuri.coding.content.mapper.coding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingInterview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApCodingInterviewMapper extends BaseMapper<ApCodingInterview> {

    /**
     * 进行中的面试（一人同时只会有一场：开面前必查）。
     *
     * @param userId 用户ID
     * @return 进行中记录；没有则返回 null
     */
    @Select("SELECT * FROM ap_coding_interview WHERE user_id = #{userId} AND status = 1 "
        + "ORDER BY id DESC LIMIT 1")
    ApCodingInterview selectOngoing(@Param("userId") Integer userId);

    /**
     * 今日已开面试场次（进行中 + 已完成；已过期不计，避免被超时锁死）。
     *
     * @param userId   用户ID
     * @param dayStart 今日 00:00:00
     */
    @Select("SELECT COUNT(*) FROM ap_coding_interview WHERE user_id = #{userId} "
        + "AND status IN (1, 2) AND started_time >= #{dayStart}")
    long countToday(@Param("userId") Integer userId,
                    @Param("dayStart") java.util.Date dayStart);
}