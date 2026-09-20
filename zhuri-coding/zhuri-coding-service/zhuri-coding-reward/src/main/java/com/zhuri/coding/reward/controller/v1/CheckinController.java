package com.zhuri.coding.reward.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import com.zhuri.coding.reward.service.CheckinService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sign")
public class CheckinController {

    @Autowired
    private CheckinService checkinService;

    /**
     * 从可信线程取当前登录用户（拦截器依据 accToken 解析），
     * 废弃原"匿名缺省为 1L"的逻辑，防止未登录冒充指定用户签到。
     */
    private ResponseResult requireUserId(java.util.function.Function<Long, ResponseResult> action) {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return action.apply(AppThreadLocalUtil.getUser().getId().longValue());
    }

    /** 获取签到状态与日历数据 */
    @GetMapping("/status")
    public ResponseResult status() {
        return requireUserId(checkinService::getStatus);
    }

    /** 执行每日签到 */
    @PostMapping("/checkin")
    public ResponseResult doCheckin() {
        return requireUserId(checkinService::doCheckin);
    }

    /** 执行补签操作 */
    @PostMapping("/extra")
    public ResponseResult doExtra(@RequestBody Map<String, String> body) {
        String targetDate = body.get("date");
        if (targetDate == null) {
            return ResponseResult.errorResult(400, "缺少补签日期");
        }
        return requireUserId(userId -> checkinService.doExtra(userId, targetDate));
    }

    /** 获取今日签到状态（侧边栏用，保留旧路径兼容） */
    @GetMapping("/today")
    public ResponseResult todayStatus() {
        return requireUserId(checkinService::getTodayStatus);
    }
}