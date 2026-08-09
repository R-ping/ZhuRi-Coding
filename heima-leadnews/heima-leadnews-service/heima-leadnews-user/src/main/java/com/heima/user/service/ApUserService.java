package com.heima.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dtos.LoginDto;
import com.heima.model.user.pojos.ApUser;

public interface ApUserService extends IService<ApUser> {
    /**
     * app端统一登录认证（支持多种登录方式）
     * @param dto 登录参数
     * @param tag 登录方式标识
     * @return 登录结果
     */
    ResponseResult allLoginAuth(LoginDto dto, String tag);
}
