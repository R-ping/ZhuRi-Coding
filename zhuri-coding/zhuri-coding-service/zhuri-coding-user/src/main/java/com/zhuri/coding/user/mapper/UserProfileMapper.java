package com.heima.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.user.pojos.UserProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}