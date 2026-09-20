
package com.heima.content.controller.v1.contentdata;

import com.heima.content.service.contentdata.ContentDataService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data")
public class ContentDataController {

    @Autowired
    private ContentDataService contentDataService;

    @GetMapping("/article/statistics")
    public ResponseResult articleStatistics(@RequestParam String startDate, @RequestParam String endDate) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getArticleStatistics(userId, startDate, endDate);
    }

    @GetMapping("/article/trend")
    public ResponseResult articleTrend(@RequestParam String startDate, @RequestParam String endDate,
                                       @RequestParam(defaultValue = "7") Integer days) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getArticleTrend(userId, startDate, endDate, days);
    }

    @GetMapping("/article/detail")
    public ResponseResult articleDetail(@RequestParam String startDate, @RequestParam String endDate,
                                        @RequestParam(defaultValue = "1") Integer page,
                                        @RequestParam(defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getArticleDetail(userId, startDate, endDate, page, size);
    }

    @GetMapping("/column/statistics")
    public ResponseResult columnStatistics(@RequestParam String startDate, @RequestParam String endDate) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getColumnStatistics(userId, startDate, endDate);
    }

    @GetMapping("/column/trend")
    public ResponseResult columnTrend(@RequestParam String startDate, @RequestParam String endDate,
                                      @RequestParam(defaultValue = "7") Integer days) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getColumnTrend(userId, startDate, endDate, days);
    }

    @GetMapping("/column/detail")
    public ResponseResult columnDetail(@RequestParam String startDate, @RequestParam String endDate,
                                       @RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getColumnDetail(userId, startDate, endDate, page, size);
    }

    @GetMapping("/pin/statistics")
    public ResponseResult pinStatistics(@RequestParam String startDate, @RequestParam String endDate) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getPinStatistics(userId, startDate, endDate);
    }

    @GetMapping("/pin/trend")
    public ResponseResult pinTrend(@RequestParam String startDate, @RequestParam String endDate,
                                   @RequestParam(defaultValue = "7") Integer days) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getPinTrend(userId, startDate, endDate, days);
    }

    @GetMapping("/pin/detail")
    public ResponseResult pinDetail(@RequestParam String startDate, @RequestParam String endDate,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        Long userId = user != null ? user.getId().longValue() : 0L;
        return contentDataService.getPinDetail(userId, startDate, endDate, page, size);
    }
}
