package com.zhuri.coding.notification.interceptor;

import com.zhuri.coding.common.auth.InternalAuthSigner;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

@Slf4j
public class AppTokenInterceptor implements HandlerInterceptor {

    /** 网关与下游共享的内部身份签名密钥（未配置则拒绝信任身份头，fail-closed） */
    private final String internalAuthSecret;

    public AppTokenInterceptor(String internalAuthSecret) {
        this.internalAuthSecret = internalAuthSecret;
        warnIfInsecure();
    }

    /**
     * 启动期自检：密钥缺失或仍为公开的开发默认值时给出告警。
     * 让"内部身份签名形同虚设"这类配置问题在启动阶段暴露，而不是等到被冒充后才发现。
     */
    private void warnIfInsecure() {
        if (!InternalAuthSigner.isConfigured(internalAuthSecret)) {
            log.error("app.internal-auth.secret 未配置，内部身份校验将以 fail-closed 运行："
                + "所有携带身份头的请求都会被判为不可信并按匿名处理。请设置 INTERNAL_AUTH_SECRET 环境变量。");
        } else if (InternalAuthSigner.DEFAULT_DEV_SECRET.equals(internalAuthSecret)) {
            log.warn("app.internal-auth.secret 仍为公开的开发默认值，生产环境务必用 "
                + "INTERNAL_AUTH_SECRET 覆盖，否则签名可被伪造。");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userId = request.getHeader("userId");
        String nickName = request.getHeader("nickName");
        String image = request.getHeader("image");
        if (userId != null && isTrusted(request, userId, nickName, image)) {
            ApUser apUser = new ApUser();
            apUser.setId(Integer.valueOf(userId));
            apUser.setNickname(decodeNickName(nickName));
            apUser.setImage(image);
            AppThreadLocalUtil.setUser(apUser);
        } else if (userId != null) {
            log.warn("内部身份签名校验失败，按匿名处理, uri={}", request.getRequestURI());
        }
        return true;
    }

    /**
     * 仅当密钥已配置且 HMAC 验签通过时才信任身份头（fail-closed），
     * 签名参数与网关保持一致：userId / nickName（解码后）/ image。
     */
    private boolean isTrusted(HttpServletRequest request, String userId, String nickName, String image) {
        if (!InternalAuthSigner.isConfigured(internalAuthSecret)) {
            return false;
        }
        String sign = request.getHeader(InternalAuthSigner.HEADER_SIGN);
        return InternalAuthSigner.verify(internalAuthSecret, sign, userId, decodeNickName(nickName), image != null ? image : "");
    }

    /**
     * 对从请求头读取的 nickName 进行 URL 解码，还原网关写入时被 URL 编码的中文昵称。
     * 解码失败时回退为原始值。
     */
    private String decodeNickName(String nickName) {
        if (nickName == null) {
            return "";
        }
        try {
            return URLDecoder.decode(nickName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return nickName;
        }
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        AppThreadLocalUtil.clear();
    }
}
