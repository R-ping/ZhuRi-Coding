package com.heima.reward.interceptor;

import com.heima.model.user.pojos.ApUser;
import com.heima.utils.common.AppJwtUtil;
import com.heima.utils.thread.AppThreadLocalUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * reward 服务 token 拦截器
 *
 * 安全目标：阻断"身份伪造"导致的越权。
 * - 从请求头 accToken 解析出【可信 userId】（网关已验签；此处再解析一次并二次校验签名/过期），
 *   只信任该值，忽略请求中可被客户端任意伪造的 userId 头/路径参数。
 * - 无有效 accToken（如服务间 Feign 直连）不注入用户线程，由各 Controller 按"内部调用"放行。
 */
@Slf4j
public class RewardTokenInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String accToken = request.getHeader("accToken");
        if (accToken != null && !accToken.isBlank()) {
            try {
                Claims claims = AppJwtUtil.getClaimsBody(accToken);
                // verifyToken：-1/0 为有效（0 临近过期仍视为有效），1/2 表示已过期或解析异常
                int verify = AppJwtUtil.verifyToken(claims);
                if (claims != null && verify <= 0) {
                    Object userIdObj = claims.get("userId");
                    if (userIdObj != null) {
                        ApUser apUser = new ApUser();
                        apUser.setId(((Number) userIdObj).intValue());
                        Object nick = claims.get("nickName");
                        apUser.setNickname(nick != null ? nick.toString() : "");
                        AppThreadLocalUtil.setUser(apUser);
                    }
                }
            } catch (Exception e) {
                // token 无效按匿名处理，不注入线程；具体拒绝策略由 Controller 决定
                log.debug("reward token 解析失败，按匿名处理: {}", request.getRequestURI());
            }
        }
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AppThreadLocalUtil.clear();
    }
}