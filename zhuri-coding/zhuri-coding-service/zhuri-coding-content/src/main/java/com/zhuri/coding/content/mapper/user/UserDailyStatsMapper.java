package com.heima.content.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.user.pojos.UserDailyStats;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserDailyStatsMapper extends BaseMapper<UserDailyStats> {
}