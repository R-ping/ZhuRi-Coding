package com.heima.apis.search.fallback;

import com.heima.apis.search.IContentSearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.UserSearchDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * content 搜索客户端降级：服务不可用时返回明确错误码，避免级联失败。
 */
@Slf4j
@Component
public class IContentSearchClientFallback implements IContentSearchClient {

    @Override
    public ResponseResult searchCourse(UserSearchDto dto) {
        log.error("远程课程搜索异常, keyword={}", dto != null ? dto.getSearchWords() : null);
        return ResponseResult.errorResult(500, "内容服务暂不可用");
    }

    @Override
    public ResponseResult searchTag(UserSearchDto dto) {
        log.error("远程标签搜索异常, keyword={}", dto != null ? dto.getSearchWords() : null);
        return ResponseResult.errorResult(500, "内容服务暂不可用");
    }
}