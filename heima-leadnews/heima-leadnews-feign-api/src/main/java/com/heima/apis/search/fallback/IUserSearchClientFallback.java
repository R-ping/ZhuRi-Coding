package com.heima.apis.search.fallback;

import com.heima.apis.search.IUserSearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.UserSearchDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * user 搜索客户端降级：服务不可用时返回明确错误码，避免级联失败。
 */
@Slf4j
@Component
public class IUserSearchClientFallback implements IUserSearchClient {

    @Override
    public ResponseResult searchUser(UserSearchDto dto) {
        log.error("远程用户搜索异常, keyword={}", dto != null ? dto.getSearchWords() : null);
        return ResponseResult.errorResult(500, "用户服务暂不可用");
    }
}