package com.heima.user.controller.v1;

import com.heima.common.annotation.RateLimit;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dtos.LoginResultVo;
import com.heima.model.user.dtos.RefreshTokenDto;
import com.heima.user.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token管理控制器
 * <p>
 * 提供 access_token 刷新、登出等接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/token")
public class TokenController {

    @Autowired
    private TokenService tokenService;

    /**
     * 刷新双token
     * <p>
     * 当 access_token 过期时，前端携带 refresh_token 调用此接口，
     * 后端验证 refresh_token 有效性，返回新的 access_token + refresh_token
     *
     * @param dto 包含 refresh_token
     * @return 新的双token
     */
    @PostMapping("/refresh")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 100, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult refreshToken(@RequestBody RefreshTokenDto dto) {
        log.info("收到刷新token请求");
        LoginResultVo result = tokenService.refreshToken(dto.getRefreshToken());
        if (result == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.TOKEN_INVALID, "refresh_token无效或已过期");
        }
        return ResponseResult.okResult(result);
    }

    /**
     * 登出（吊销refresh_token）
     *
     * @param dto 包含 refresh_token
     * @return 操作结果
     */
    @PostMapping("/logout")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 50, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult logout(@RequestBody RefreshTokenDto dto) {
        log.info("收到登出请求");
        tokenService.revokeRefreshToken(dto.getRefreshToken());
        return ResponseResult.okResult(AppHttpCodeEnum.SUCCESS);
    }
}
