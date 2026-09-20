package com.heima.user.service;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dtos.SocialAuthDto;
import com.heima.model.user.dtos.SocialBindDto;

/**
 * 社交登录核心业务服务
 */
public interface SocialLoginService {

    /**
     *
     * @return 登录结果
     */
    ResponseResult socialAuth(SocialAuthDto dto);

    /**
     *
     * @return 登录结果
     */
    ResponseResult socialBind(SocialBindDto dto);

    String checkSocialBind(String phone, String platform, String tag);
}
