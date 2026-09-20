package com.zhuri.coding.content.mapper.achievement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.achievement.pojos.ApUserAchievement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户成就解锁记录 Mapper（事件驱动写入，查询只读）
 */
@Mapper
public interface ApUserAchievementMapper extends BaseMapper<ApUserAchievement> {

    /**
     * 查询单条用户成就记录（幂等检查用）
     */
    @Select("SELECT * FROM ap_user_achievement WHERE user_id = #{userId} AND achievement_code = #{code}")
    ApUserAchievement selectByUserAndCode(@Param("userId") Long userId, @Param("code") String code);
}
