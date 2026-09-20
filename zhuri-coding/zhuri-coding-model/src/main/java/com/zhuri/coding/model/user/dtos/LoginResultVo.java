package com.heima.model.user.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 双Token登录结果 VO
 * <p>
 * 包含 access_token（JWT） + refresh_token（UUID） + 用户基本信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultVo {

    /**
     * 业务状态：
     * "login"     - 已登录，直接返回token
     * "need_bind" - 新用户，需选择"绑定已有账号"或"注册新号"
     */
    private String status;

    /**
     * JWT access_token
     */
    private String accessToken;

    /**
     * UUID refresh_token
     */
    private String refreshToken;
    private String platform;
    private String platformUid;
    /**
     * 用户ID
     */
    private Integer userId;

    /**
     * 昵称
     */
    private String nickName;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 手机号
     */
    private String phone;

}
