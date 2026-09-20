package com.zhuri.coding.reward.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.reward.service.VirtualAssetService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 用户虚拟道具控制器
 * <p>
 * - 外部（用户）访问「我的道具」列表；
 * - content 服务经 Feign 直连进行持有校验与核销（仅内部调用，防止伪造 userId 越权）。
 */
@RestController
public class VirtualAssetController {

    @Autowired
    private VirtualAssetService virtualAssetService;

    /** 从可信线程取当前登录用户（拦截器依据 accToken 解析） */
    private ResponseResult requireUserId(java.util.function.Function<Long, ResponseResult> action) {
        if (AppThreadLocalUtil.getUser() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return action.apply(AppThreadLocalUtil.getUser().getId().longValue());
    }

    /** 判断当前请求是否为【外部用户】调用（而非服务间 Feign 直连） */
    private boolean isExternalCall() {
        return AppThreadLocalUtil.getUser() != null;
    }

    /** 外部查询：我的道具列表（"我的道具"分栏） */
    @GetMapping("/api/v1/virtual-assets")
    public ResponseResult listMyAssets() {
        return requireUserId(virtualAssetService::listMyAssets);
    }

    /** 内部查询：校验用户是否持有指定虚拟道具并返回折扣（content 下单前调用） */
    @GetMapping("/api/v1/reward/user/{userId}/virtual-asset/hold")
    public ResponseResult hold(@PathVariable("userId") Long userId,
                               @RequestParam("itemCode") String itemCode) {
        if (isExternalCall()) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        return virtualAssetService.getHold(userId, itemCode);
    }

    /** 内部核销：扣减用户虚拟道具（content 支付成功后调用） */
    @PostMapping("/api/v1/reward/user/{userId}/virtual-asset/consume")
    public ResponseResult consume(@PathVariable("userId") Long userId,
                                  @RequestParam("itemCode") String itemCode,
                                  @RequestParam(value = "count", defaultValue = "1") int count) {
        if (isExternalCall()) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        return virtualAssetService.consume(userId, itemCode, count);
    }
}