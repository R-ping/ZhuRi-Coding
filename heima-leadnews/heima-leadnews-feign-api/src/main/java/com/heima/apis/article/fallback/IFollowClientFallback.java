package com.heima.apis.article.fallback;

import com.heima.apis.article.IFollowClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class IFollowClientFallback implements IFollowClient {

    @Override
    public ResponseResult follow(Long userId, Long followUserId) {
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "关注服务不可用");
    }

    @Override
    public ResponseResult isFollowing(Long userId, Long followUserId) {
        // 降级返回 false，避免开阻塞发送；IM 侧需兼容降级结果
        return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "关注关系查询不可用");
    }
}