package com.heima.app.gateway.filter;


import com.heima.app.gateway.util.AppJwtUtil;
import io.jsonwebtoken.Claims;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizeFilter implements Ordered, GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1.获取request和response对象
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        String path = request.getURI().getPath();
        //2.判断是否是登录/注册/token刷新/社交登录相关接口（放行）
        // 注意：使用精确前缀/后缀匹配，避免 path.contains() 被路径中包含关键词的任意请求绕过
        if (path.equals("/api/v1/login")
            || path.equals("/api/v1/login_auth")
            || path.startsWith("/api/v1/oauth2/")
            || path.startsWith("/api/v1/token/")
            || path.startsWith("/user/api/v1/token/")
            || path.startsWith("/api/v1/login/")
            || path.startsWith("/user/api/v1/login/")
            || path.startsWith("/content/api/v1/pins/list")
            || path.startsWith("/content/api/v1/pins/circles")
            || path.startsWith("/content/api/v1/topics/")
            || path.startsWith("/content/api/v1/content/recommend")
            || path.startsWith("/content/api/v1/article/recommend")) {
            //放行
            return chain.filter(exchange);
        }
        //3.获取accToken
        // accToken不存在，或过期，或校验不通过，都放回444，
        // 前端捕获到444后，应调用 /api/v1/token/refresh 接口用refToken刷新双token，再重放请求
        String accToken = request.getHeaders().getFirst("accToken");

        //4.判断token是否存在
        if (StringUtils.isBlank(accToken)) {
            response.setStatusCode(HttpStatusCode.valueOf(444));
            return response.setComplete();
        }
        //5.判断token是否有效
        try {
            Claims claimsBody = AppJwtUtil.getClaimsBody(accToken);
            //是否是过期
            boolean result = AppJwtUtil.verifyToken(claimsBody);
            if (!result) {
                response.setStatusCode(HttpStatusCode.valueOf(444));
                return response.setComplete();
            }
            //获取用户信息
            Object userId = claimsBody.get("userId");
            String nickName = (String) claimsBody.get("nickName");
            //存储header中
            ServerHttpRequest serverHttpRequest = request.mutate().headers(httpHeaders -> {
                httpHeaders.add("userId", userId.toString());
                httpHeaders.add("nickName", nickName);
            }).build();
            //重置请求
            exchange = exchange.mutate().request(serverHttpRequest).build();
        } catch (Exception e) {
//            e.printStackTrace();
            log.error("app端jwt解析失败：" + e.getMessage());
            response.setStatusCode(HttpStatusCode.valueOf(444));
            return response.setComplete();
        }
        //6.放行
        return chain.filter(exchange);
    }

    /**
     * 优先级设置  值越小  优先级越高
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
