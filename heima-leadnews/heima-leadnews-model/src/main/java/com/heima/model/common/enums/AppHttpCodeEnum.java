package com.heima.model.common.enums;

public enum AppHttpCodeEnum {

    // 成功段固定为200
    SUCCESS(200,"操作成功"),
    // 登录段1~50
    NEED_LOGIN(1,"需要登录后操作"),
    LOGIN_PASSWORD_ERROR(2,"密码错误"),
    // TOKEN50~100
    TOKEN_INVALID(50,"无效的TOKEN"),
    TOKEN_EXPIRE(51,"TOKEN已过期"),
    TOKEN_REQUIRE(52,"TOKEN是必须的"),
    // SIGN验签 100~120
    SIGN_INVALID(100,"无效的SIGN"),
    SIG_TIMEOUT(101,"SIGN已过期"),
    // 参数错误 500~1000
    PARAM_REQUIRE(500,"缺少参数"),
    PARAM_INVALID(501,"无效参数"),
    PARAM_IMAGE_FORMAT_ERROR(502,"图片格式有误"),
    // 审核失败 500~600
    APPROVE_FAILED(500,"审核失败"),
    SERVER_ERROR(503,"服务器内部错误"),
    // 数据错误 1000~2000
    DATA_EXIST(1000,"数据已经存在"),
    AP_USER_DATA_NOT_EXIST(1001,"用户数据不存在或已锁定"),
    DATA_NOT_EXIST(1002,"数据不存在"),
    // 社交登录 2000~2100
    SOCIAL_ALREADY_BOUND(2001,"该社交账号已绑定其他用户"),
    SOCIAL_ACCOUNT_BOUND_OTHER(2002,"该手机号账号已绑定其他社交账号，无法重复绑定"),
    SOCIAL_TEMP_TOKEN_INVALID(2003,"临时凭证无效或已过期，请重新登录"),
    SOCIAL_USERNAME_EXIST(2004,"用户名已存在"),
    SOCIAL_PLATFORM_EXIST(2005,"平台uId已存在"),
    SOCIAL_PHONE_BOUND_OTHER(2006,"手机号已绑定该类型社交账号"),
    LOGIN_CODE_ERROR(2007,"登录验证码错误或失效"),
    // 数据错误 3000~3500
    NO_OPERATOR_AUTH(3000,"无权限操作"),
    NEED_ADMIND(3001,"需要管理员权限"),

    // 订单/支付错误 3200~3250
    ORDER_CLOSED(3200,"订单已关闭，请重新下单"),
    ORDER_STATUS_INVALID(3201,"订单状态异常"),

    // 自媒体文章错误 3501~3600
    MATERIASL_REFERENCE_FAIL(3501,"素材引用失效"),

    // 限流错误 8001~8100
    RATE_LIMIT_EXCEEDED(8001,"请求过于频繁，请稍后再试");


    int code;
    String errorMessage;

    AppHttpCodeEnum(int code, String errorMessage){
        this.code = code;
        this.errorMessage = errorMessage;
    }

    public int getCode() {
        return code;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
