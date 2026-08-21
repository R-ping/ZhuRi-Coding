package com.heima.reward.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.utils.thread.AppThreadLocalUtil;
import com.heima.reward.service.LotteryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/lottery")
public class LotteryController {

    @Autowired
    private LotteryService lotteryService;

    /**
     * 从可信线程取当前登录用户（拦截器依据 accToken 解析），
     * 废弃原"匿名缺省为 1L"的逻辑，防止未登录冒充指定用户抽奖/领奖。
     */
    private ResponseResult requireUserId(java.util.function.Function<Long, ResponseResult> action) {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return action.apply(AppThreadLocalUtil.getUser().getId().longValue());
    }

    /** 获取抽奖页面数据 */
    @GetMapping("/dashboard")
    public ResponseResult dashboard() {
        return requireUserId(lotteryService::getDashboard);
    }

    /** 执行抽奖 */
    @PostMapping("/draw")
    public ResponseResult draw(@RequestBody Map<String, Object> body) {
        String type = (String) body.get("type");
        Boolean useFree = (Boolean) body.get("useFree");
        return requireUserId(userId -> lotteryService.draw(userId, type, useFree));
    }

    /** 领取实物奖品 */
    @PostMapping("/claim-physical")
    public ResponseResult claimPhysical(@RequestBody Map<String, Object> body) {
        return requireUserId(userId -> lotteryService.claimPhysical(userId, body));
    }

    /** 获取我的收获 */
    @GetMapping("/my-prizes")
    public ResponseResult myPrizes(@RequestParam(defaultValue = "1") Integer page,
                                   @RequestParam(defaultValue = "20") Integer size,
                                   @RequestParam(defaultValue = "all") String type) {
        return requireUserId(userId -> lotteryService.getMyPrizes(userId, page, size, type));
    }

    /** 获取中奖播报 */
    @GetMapping("/broadcast/recent")
    public ResponseResult broadcast() {
        return lotteryService.getBroadcast();
    }
}
