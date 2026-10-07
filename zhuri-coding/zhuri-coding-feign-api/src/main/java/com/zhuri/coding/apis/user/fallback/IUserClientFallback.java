package com.zhuri.coding.apis.user.fallback;

import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class IUserClientFallback implements FallbackFactory<IUserClient> {
    @Override
    public IUserClient create(Throwable cause) {
        return new IUserClient() {
            @Override
            public ResponseResult getBasicInfo(Long userId) {
                log.error("IUserClient.getBasicInfo fallback, userId={}, error: {}", userId, cause.getMessage());
                return ResponseResult.errorResult(500, "用户服务不可用");
            }

            @Override
            public ResponseResult getPublicInfo(Long userId) {
                log.error("IUserClient.getPublicInfo fallback, userId={}, error: {}", userId, cause.getMessage());
                return ResponseResult.errorResult(500, "用户服务不可用");
            }

            @Override
            public ResponseResult getBasicInfoBatch(List<Long> userIds) {
                log.error("IUserClient.getBasicInfoBatch fallback, size={}, error: {}",
                        userIds == null ? 0 : userIds.size(), cause.getMessage());
                return ResponseResult.errorResult(500, "用户服务不可用");
            }

            @Override
            public ResponseResult getValidUserIds(List<Long> userIds) {
                log.error("IUserClient.getValidUserIds fallback, size={}, error: {}",
                        userIds == null ? 0 : userIds.size(), cause.getMessage());
                // 刻意 fail-open：无法判定有效性时按"全部有效"返回 ——
                // 给已注销账号多写一条无人可见的站内信，代价远小于给所有正常用户漏发
                return ResponseResult.okResult(userIds == null ? List.of() : userIds);
            }

            @Override
            public ResponseResult getAdminRoles(Long accountId) {
                log.error("IUserClient.getAdminRoles fallback, accountId={}, error: {}", accountId, cause.getMessage());
                // ⚠️ 与上一处刻意相反，这里必须 fail-closed：返回空列表 = "不是运营"，
                // 于是所有运营接口对该账号一律 403。反过来（fail-open 给全权限）
                // 会在用户服务故障时把整个运营后台向所有人敞开 —— 这是不可接受的降级。
                // 代价只是"用户服务挂了期间运营也用不了后台"，恰好我们也确实用不了。
                return ResponseResult.okResult(List.of());
            }
        };
    }
}