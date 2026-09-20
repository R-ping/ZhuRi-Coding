package com.zhuri.coding.user.controller.v1;

import cn.hutool.core.util.StrUtil;
import com.zhuri.coding.common.annotation.RateLimit;
import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.dtos.LoginDto;
import com.zhuri.coding.model.user.dtos.SocialBindDto;
import com.zhuri.coding.user.service.ApUserService;
import com.zhuri.coding.user.service.SocialLoginService;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/login")
@Slf4j
public class ApUserLoginController {

    @Autowired
    private ApUserService apUserService;
    @Autowired
    private SocialLoginService socialLoginService;
    @Autowired
    private CacheService cacheService;

    /** 同一手机号验证码发送最小间隔（秒），防短信轰炸 */
    private static final long SMS_INTERVAL_SECONDS = 60;

    /**
     * 是否在响应体中回传登录验证码。
     * <p>
     * 本项目没有接真实短信通道，前端依赖回传值自动填充以便本地联调（见 login_modal.vue）。
     * 因此该开关默认开启，但<b>生产环境必须通过 AUTH_EXPOSE_LOGIN_CODE=false 关闭</b>，
     * 否则任何人无需接收短信即可拿到验证码完成登录/绑定。
     */
    @Value("${app.auth.expose-login-code:true}")
    private boolean exposeLoginCode;

    /**
     * 启动期自检：开启回传验证码时给出告警，避免生产环境带着演示开关上线。
     */
    @PostConstruct
    public void warnIfExposingLoginCode() {
        if (exposeLoginCode) {
            log.warn("app.auth.expose-login-code=true：验证码会随响应体明文返回，仅适用于本地/演示环境；"
                + "生产环境请设置 AUTH_EXPOSE_LOGIN_CODE=false 关闭。");
        }
    }

    /**
     * 1、手机号验证码 登录/注册
     * 2、手机号/邮箱 + 密码 登录
     */
    @PostMapping("/login_auth")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult login(@RequestBody LoginDto dto) {
        // 参数校验：phoneOrEmail 为登录入口必填，缺失时直接返回业务错误，
        // 避免后续 phoneOrEmail.contains() 空指针被全局处理器误报为"服务器错误 503"
        String phoneOrEmail = dto.getPhoneOrEmail();
        if (StrUtil.isBlank(phoneOrEmail)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }
        // 确定具体流程
        String tag;
        if (phoneOrEmail.contains("@")) {
            // 邮箱+密码登录
            tag = "emailPass";
        } else if (StrUtil.isNotBlank(dto.getPassword())) {
            // 手机号+密码登录
            tag = "phonePass";
        } else {
            // 手机号验证码登录/注册
            tag = "phoneCode";
        }
        return apUserService.allLoginAuth(dto, tag);
    }

    /**
     * 已有账号 → 绑定社交账号
     * <p>
     * 社交登录的后置，绑定账号操作，手机号验证码的方式，
     */
    @PostMapping("/social_bind")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult socialBind(@RequestBody SocialBindDto dto) {
        log.info("收到社交绑定请求: phone={}", maskPhone(dto.getPhone()));
        // 1. 参数校验
        if (StringUtils.isAnyBlank(dto.getPlatform(), dto.getPlatformUid(), dto.getPhone(), dto.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE);
        }
        return socialLoginService.socialBind(dto);
    }

    /**
     * 获取验证码接口，简单做，给uuid就行
     *
     * @param tag:"login" 手机号验证码的登录/注册功能
     * @param tag:"bind" 手机号验证码，绑定社交账号功能，需校验手机号是否已绑定
     */
    @PostMapping("/code")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult getCode(String phone, String platform, String tag) {
        if (StrUtil.isBlank(phone) || StrUtil.isBlank(platform)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        // 同一手机号 60 秒内仅允许发送一次，防针对单号的短信轰炸
        String intervalKey = "sms:interval:" + phone;
        Boolean firstTime = cacheService.getstringRedisTemplate()
                .opsForValue().setIfAbsent(intervalKey, "1", Duration.ofSeconds(SMS_INTERVAL_SECONDS));
        if (!Boolean.TRUE.equals(firstTime)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "发送过于频繁，请60秒后再试");
        }
        // 手机号脱敏后记录，避免完整号码落日志
        log.info("收到获取验证码请求: phone={}", maskPhone(phone));
        String resultCode = socialLoginService.checkSocialBind(phone, platform, tag);
        if (StrUtil.isBlank(resultCode)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SOCIAL_PHONE_BOUND_OTHER);
        }
        // 生产环境不回传验证码，前端会降级提示"验证码已发送"
        return ResponseResult.okResult(exposeLoginCode ? resultCode : "");
    }

    /** 手机号脱敏：保留前 3 位与后 4 位 */
    private String maskPhone(String phone) {
        if (StrUtil.isBlank(phone) || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
