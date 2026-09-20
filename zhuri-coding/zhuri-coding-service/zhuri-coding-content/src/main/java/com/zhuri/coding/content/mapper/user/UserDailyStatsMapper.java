package com.zhuri.coding.content.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.user.pojos.UserDailyStats;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserDailyStatsMapper extends BaseMapper<UserDailyStats> {
}