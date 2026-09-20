package com.heima.search.controller.v1;

import com.heima.apis.search.IContentSearchClient;
import com.heima.apis.search.IUserSearchClient;
import com.heima.common.annotation.RateLimit;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.SearchDto;
import com.heima.model.search.dtos.UserSearchDto;
import com.heima.search.service.ArticleSearchService;
import java.io.IOException;
import java.util.Date;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一搜索聚合入口（对齐掘金单一 /search 接口，通过 id_type 区分分栏）。
 *
 * <p>路径：POST /api/v1/search（网关前缀 /search → 完整 /search/api/v1/search）。
 * 参数见 {@link SearchDto}。</p>
 * <p>分发策略：
 * <ul>
 *   <li>idType 0 综合 / 1 文章 → 本服务本地 ES 检索引擎</li>
 *   <li>idType 2 课程 → Feign 调 content 服务 /api/v1/search/course</li>
 *   <li>idType 3 标签 → Feign 调 content 服务 /api/v1/search/tag</li>
 *   <li>idType 4 用户 → Feign 调 user 服务 /api/v1/search/user</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @Autowired
    private ArticleSearchService articleSearchService;
    @Autowired
    private IContentSearchClient contentSearchClient;
    @Autowired
    private IUserSearchClient userSearchClient;

    @PostMapping("")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 300, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseResult search(@RequestBody SearchDto dto) throws IOException {
        if (dto == null || StringUtils.isBlank(dto.getQuery())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "搜索关键字不能为空");
        }
        Integer idType = dto.getIdType() == null ? 0 : dto.getIdType();
        int pageNum = dto.getPageNum() > 0 ? dto.getPageNum() : 1;
        int pageSize = dto.getPageSize() > 0 ? dto.getPageSize() : 20;

        // 构建下游各服务统一复用的分页查询参数（UserSearchDto）
        UserSearchDto param = new UserSearchDto();
        param.setSearchWords(dto.getQuery().trim());
        param.setPageNum(pageNum);
        param.setPageSize(pageSize);

        switch (idType) {
            case 2: // 课程 → content 服务
                return contentSearchClient.searchCourse(param);
            case 3: // 标签 → content 服务
                return contentSearchClient.searchTag(param);
            case 4: // 用户 → user 服务
                return userSearchClient.searchUser(param);
            default: // 0 综合 / 1 文章 → 本服务 ES
                if (param.getMinBehotTime() == null) {
                    param.setMinBehotTime(new Date());
                }
                return articleSearchService.search(param);
        }
    }
}