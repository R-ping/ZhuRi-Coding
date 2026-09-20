package com.heima.content.controller.v1.browse;

import com.heima.content.service.browse.BrowseHistoryService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/browse-history")
public class BrowseHistoryController {

    @Autowired
    private BrowseHistoryService browseHistoryService;

    @GetMapping("/list")
    public ResponseResult getHistoryList(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = user.getId().longValue();
        return browseHistoryService.getHistoryList(userId, page, size, keyword);
    }

    @PostMapping("/clear")
    public ResponseResult clearHistory() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = user.getId().longValue();
        browseHistoryService.clearHistory(userId);
        return ResponseResult.okResult();
    }

    @PostMapping("/report")
    public ResponseResult reportBrowse(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long userId = user.getId().longValue();
        Integer targetType = params.get("targetType") != null ? Integer.valueOf(params.get("targetType").toString()) : null;
        Long targetId = params.get("targetId") != null ? Long.valueOf(params.get("targetId").toString()) : null;
        return browseHistoryService.reportBrowse(userId, targetType, targetId);
    }
}