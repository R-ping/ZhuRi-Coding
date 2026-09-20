
package com.heima.content.controller.v1.fans;

import com.heima.content.service.fans.FansDataService;
import com.heima.model.common.dtos.ResponseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/data/fans")
public class FansDataController {

    @Autowired
    private FansDataService fansDataService;

    @GetMapping("/statistics")
    public ResponseResult getStatistics(@RequestParam String startDate, @RequestParam String endDate) {
        return fansDataService.getFansStatistics(startDate, endDate);
    }

    @GetMapping("/trend")
    public ResponseResult getTrend(@RequestParam String startDate, @RequestParam String endDate,
                                   @RequestParam(defaultValue = "7") Integer days) {
        return fansDataService.getFansTrend(startDate, endDate, days);
    }

    @GetMapping("/list")
    public ResponseResult getList(@RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "10") Integer size) {
        return fansDataService.getFansList(page, size);
    }

    @PostMapping("/follow")
    public ResponseResult follow(@RequestBody Map<String, Object> body) {
        Integer userId = body.get("userId") != null ? ((Number) body.get("userId")).intValue() : null;
        return fansDataService.followFans(userId);
    }

    @GetMapping("/portrait")
    public ResponseResult getPortrait() {
        return fansDataService.getFansPortrait();
    }

    @GetMapping("/avatars")
    public ResponseResult getAvatars(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size) {
        return fansDataService.getFansAvatars(page, size);
    }
}
