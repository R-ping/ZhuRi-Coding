package com.zhuri.coding.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dtos.LoginDto;
import com.zhuri.coding.model.user.pojos.ApUser;

public interface ApUserService extends IService<ApUser> {
    /**
     * app端统一登录认证（支持多种登录方式）
     * @param dto 登录参数
     * @param tag 登录方式标识
     * @return 登录结果
     */
    ResponseResult allLoginAuth(LoginDto dto, String tag);

    /**
     * 用户搜索：按昵称 LIKE 分页查询正常(1)状态的用户
     * @param keyword 昵称关键词，null/空 表示不过滤
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return okResult(list)，list 项含 id/title(=nickname)/name/authorName/authorAvatar 等字段（不含敏感字段）
     */
    ResponseResult searchUser(String keyword, Integer page, Integer size);
}
