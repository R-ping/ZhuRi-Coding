package com.heima.content.interceptor;

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
public class ContentTokenInterceptor implements HandlerInterceptor {

    /** 网关与下游共享的内部身份签名密钥（未配置则拒绝信任身份头，fail-closed） */
    private final String internalAuthSecret;

    public ContentTokenInterceptor(String internalAuthSecret) {
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

    /**
     * 得到header中的用户信息，并且存入到当前线程中。
     * 仅当身份头通过内部 HMAC 签名校验后才信任，防止绕过网关伪造 userId 头冒充用户。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userId = request.getHeader("userId");
        String nickName = request.getHeader("nickName");
        String image = request.getHeader("image");
        if (userId != null && isTrusted(request, userId, nickName, image)) {
            //存入到当前线程中
            ApUser apUser = new ApUser();
            apUser.setId(Integer.valueOf(userId));
            apUser.setNickname(decodeNickName(nickName));
            apUser.setImage(image);
            AppThreadLocalUtil.setUser(apUser);
        } else if (userId != null) {
            // 携带了 userId 头但签名无效/缺失 → 按匿名处理，不信任伪造身份
            log.warn("内部身份签名校验失败，按匿名处理, uri={}", request.getRequestURI());
        }
        return true;
    }

    /**
     * 仅当密钥已配置且 HMAC 验签通过时才信任身份头（fail-closed）。
     * 密钥未配置时一律不信任，避免绕过网关伪造 userId 头冒充用户。
     */
    private boolean isTrusted(HttpServletRequest request, String userId, String nickName, String image) {
        if (!InternalAuthSigner.isConfigured(internalAuthSecret)) {
            return false;
        }
        String sign = request.getHeader(InternalAuthSigner.HEADER_SIGN);
        // 网关签名基于原始昵称；此处用解码后的昵称参与校验
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

    /**
     * 清理线程中的数据
     * @param request
     * @param response
     * @param handler
     * @param modelAndView
     * @throws Exception
     */
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        AppThreadLocalUtil.clear();
    }
}
