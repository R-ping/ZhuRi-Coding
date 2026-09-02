package com.heima.notification.interceptor;

import com.heima.common.auth.InternalAuthSigner;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

@Slf4j
public class AppTokenInterceptor implements HandlerInterceptor {

    /** 网关与下游共享的内部身份签名密钥（未配置则跳过校验，兼容本地直连） */
    private final String internalAuthSecret;

    public AppTokenInterceptor(String internalAuthSecret) {
        this.internalAuthSecret = internalAuthSecret;
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

    /** 密钥未配置时降级信任（本地直连）；配置后必须通过 HMAC 验签（与网关签名参数一致：userId/nickName/image） */
    private boolean isTrusted(HttpServletRequest request, String userId, String nickName, String image) {
        if (internalAuthSecret == null || internalAuthSecret.isEmpty()) {
            return true;
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
