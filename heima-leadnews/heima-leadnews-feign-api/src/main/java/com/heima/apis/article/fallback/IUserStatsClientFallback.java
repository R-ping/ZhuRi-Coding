package com.heima.apis.article.fallback;

import com.heima.apis.article.IUserStatsClient;
import com.heima.model.user.vo.UserStatsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户内容统计降级：内容服务不可用时返回空统计，避免主链路级联失败。
 */
@Slf4j
@Component
public class IUserStatsClientFallback implements IUserStatsClient {

    @Override
    public UserStatsVO stats(Long userId) {
        log.warn("IUserStatsClient.stats 降级（内容服务不可用），userId={}", userId);
        UserStatsVO vo = new UserStatsVO();
        vo.setUserId(userId != null ? userId : 0L);
        return vo;
    }
}