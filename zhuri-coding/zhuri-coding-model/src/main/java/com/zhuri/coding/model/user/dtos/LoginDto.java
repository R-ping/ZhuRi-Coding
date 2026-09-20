package com.heima.model.user.dtos;


import lombok.Data;

@Data
public class LoginDto {

    /**
     * 手机号/邮箱
     */
    private String phoneOrEmail;
    /**
     * 密码
     */
    private String password;
    /**
     * 验证码
     */
    private String code;
    /**
     * 平台
     */
    private String platform;
}
