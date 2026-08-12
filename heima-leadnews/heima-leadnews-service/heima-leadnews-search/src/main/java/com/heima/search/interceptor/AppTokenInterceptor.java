package com.heima.search.interceptor;

import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;



public class AppTokenInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userId = request.getHeader("userId");
        String nickName = request.getHeader("nickName");
        if(userId != null){
            //存入到当前线程中
            ApUser apUser = new ApUser();
            apUser.setId(Integer.valueOf(userId));
            apUser.setNickname(decodeNickName(nickName));
            AppThreadLocalUtil.setUser(apUser);

        }
        return true;
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
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        AppThreadLocalUtil.clear();
    }
}
