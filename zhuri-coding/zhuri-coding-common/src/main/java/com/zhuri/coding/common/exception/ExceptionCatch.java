package com.heima.common.exception;


import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletResponse;

@ControllerAdvice  //控制器增强类
@Slf4j
public class ExceptionCatch {

    /**
     * 处理 Feign 调用异常
     * 提取 Feign 响应中的错误信息返回给客户端
     */
    @ExceptionHandler(FeignException.class)
    @ResponseBody
    public ResponseResult handleFeignException(FeignException e, HttpServletResponse response) {
        log.error("Feign调用异常: ", e);
        response.setStatus(HttpServletResponse.SC_OK);
        // 提取 Feign 响应中的错误信息
        String errorMsg = "服务调用失败";
        try {
            java.nio.ByteBuffer responseBody = e.responseBody().orElse(null);
            if (responseBody != null && responseBody.hasRemaining()) {
                byte[] bytes = new byte[responseBody.remaining()];
                responseBody.get(bytes);
                String bodyStr = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                // 尝试解析 Feign 返回的 ResponseResult
                com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(bodyStr);
                if (json != null && json.containsKey("message")) {
                    errorMsg = json.getString("message");
                    log.error("Feign调用异常，fallback一次信息是：{}",errorMsg);
                }
            }
        } catch (Exception ignored) {
            // 解析失败使用默认错误信息
        }
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, errorMsg);
    }

    /**
     * 处理限流异常
     */
    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseBody
    public ResponseResult handleRateLimitExceeded(RateLimitExceededException e, HttpServletResponse response){
        log.warn("限流触发: {}", e.getMessage());
        response.setStatus(HttpServletResponse.SC_OK);
        return ResponseResult.errorResult(AppHttpCodeEnum.RATE_LIMIT_EXCEEDED);
    }

    /**
     * 处理不可控异常
     * @param e
     * @return
     */
    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseResult exception(Exception e, HttpServletResponse response){
        log.error("catch exception:", e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR);
    }

    /**
     * 处理可控异常  自定义异常
     * @param e
     * @return
     */
    @ExceptionHandler(CustomException.class)
    @ResponseBody
    public ResponseResult exception(CustomException e, HttpServletResponse response){
        log.error("catch custom exception:", e);
        response.setStatus(mapHttpStatus(e.getAppHttpCodeEnum()));
        return ResponseResult.errorResult(e.getAppHttpCodeEnum());
    }

    /**
     * 将 AppHttpCodeEnum 映射为 HTTP 状态码
     * 映射规则：
     *   1-2:   登录错误 → 401
     *   50-52: Token错误 → 401
     *   100-101: 签名错误 → 401
     *   500-503: 参数/审核/服务器错误 → 400
     *   1000-1002: 数据错误 → 404
     *   2001-2007: 社交登录错误 → 400
     *   3000-3001: 权限错误 → 403
     *   3501:   素材错误 → 400
     */
    private int mapHttpStatus(AppHttpCodeEnum appHttpCodeEnum) {
        if (appHttpCodeEnum == null) {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        int code = appHttpCodeEnum.getCode();
        // 登录段 1~2
        if (code >= 1 && code <= 2) {
            return HttpServletResponse.SC_UNAUTHORIZED; // 401
        }
        // Token段 50~52
        if (code >= 50 && code <= 52) {
            return HttpServletResponse.SC_UNAUTHORIZED; // 401
        }
        // 签名段 100~101
        if (code >= 100 && code <= 101) {
            return HttpServletResponse.SC_UNAUTHORIZED; // 401
        }
        // 参数/审核/服务器错误 500~503
        if (code >= 500 && code <= 503) {
            return HttpServletResponse.SC_BAD_REQUEST; // 400
        }
        // 数据错误 1000~1002
        if (code >= 1000 && code <= 1002) {
            return HttpServletResponse.SC_NOT_FOUND; // 404
        }
        // 社交登录错误 2001~2007
        if (code >= 2001 && code <= 2007) {
            return HttpServletResponse.SC_BAD_REQUEST; // 400
        }
        // 权限错误 3000~3001
        if (code >= 3000 && code <= 3001) {
            return HttpServletResponse.SC_FORBIDDEN; // 403
        }
        // 素材错误 3501
        if (code == 3501) {
            return HttpServletResponse.SC_BAD_REQUEST; // 400
        }
        return HttpServletResponse.SC_INTERNAL_SERVER_ERROR; // 500
    }
}
