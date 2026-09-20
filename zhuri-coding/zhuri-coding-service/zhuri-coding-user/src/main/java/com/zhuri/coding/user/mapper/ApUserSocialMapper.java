package com.heima.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.user.pojos.ApUserSocial;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户社交账号绑定 Mapper
 */
@Mapper
public interface ApUserSocialMapper extends BaseMapper<ApUserSocial> {
}
