package com.heima.notification.websocket;

import com.heima.utils.common.AppJwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Slf4j
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final String TOKEN_KEY = "token";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null || !query.contains(TOKEN_KEY + "=")) {
            log.warn("WebSocket handshake rejected: missing token, uri={}", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String token = extractToken(query);
        if (token == null || token.isEmpty()) {
            log.warn("WebSocket handshake rejected: empty token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            Claims claims = AppJwtUtil.getClaimsBody(token);
            if (claims == null) {
                log.warn("WebSocket handshake rejected: invalid or expired token");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            // 签发端（AppJwtUtil.getToken）写入的 claim key 是 "userId"，
            // 兼容历史 "id" key 兜底，避免标准 token 因取不到用户而被拒握手
            Object userIdObj = claims.get("userId");
            if (userIdObj == null) {
                userIdObj = claims.get("id");
            }
            if (userIdObj == null) {
                log.warn("WebSocket handshake rejected: no userId in token");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            Long userId = Long.valueOf(userIdObj.toString());
            attributes.put("userId", userId);
            log.info("WebSocket handshake authenticated: userId={}", userId);
            return true;
        } catch (Exception e) {
            log.error("WebSocket handshake rejected: token parse error", e);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    private String extractToken(String query) {
        int tokenIdx = query.indexOf(TOKEN_KEY + "=");
        if (tokenIdx < 0) return null;
        String token = query.substring(tokenIdx + TOKEN_KEY.length() + 1);
        int ampIdx = token.indexOf("&");
        if (ampIdx >= 0) {
            token = token.substring(0, ampIdx);
        }
        return token;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}