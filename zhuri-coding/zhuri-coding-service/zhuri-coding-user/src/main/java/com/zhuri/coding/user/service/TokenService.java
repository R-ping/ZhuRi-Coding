package com.heima.user.service;

import com.heima.model.user.dtos.LoginResultVo;

/**
 * 双Token认证服务
 * <p>
 * access_token: JWT（有效期1小时），携带用户ID等基本信息
 * refresh_token: UUID（有效期7天），存储在Redis中，与用户信息绑定
 * <p>
 * 刷新流程：
 * 1. access_token过期 → 前端调用 refresh 接口，携带 refresh_token
 * 2. 后端验证 refresh_token 在Redis中是否存在
 * 3. 若存在，生成新的 access_token + refresh_token 返回
 * 4. 同时删除旧的 refresh_token
 */
public interface TokenService {

    /**
     * 为用户生成双token并返回
     *
     * @param userId   用户ID
     * @param userName 用户名
     * @param phone    手机号
     * @param image    头像
     * @return 包含 access_token + refresh_token 的登录结果
     */
    LoginResultVo generateDualToken(Integer userId, String userName, String phone, String image);

    /**
     * 用 refresh_token 刷新双token
     *
     * @param refreshToken UUID refresh_token
     * @return 新的双token，如果 refresh_token 无效则返回 null
     */
    LoginResultVo refreshToken(String refreshToken);

    /**
     * 吊销 refresh_token（登出时使用）
     *
     * @param refreshToken UUID refresh_token
     */
    void revokeRefreshToken(String refreshToken);
}