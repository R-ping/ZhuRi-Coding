package com.heima.reward.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.utils.thread.AppThreadLocalUtil;
import com.heima.reward.entity.UserAssets;
import com.heima.reward.mapper.UserAssetsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reward")
public class UserAssetsController {

    @Autowired
    private UserAssetsMapper userAssetsMapper;

    /**
     * 判断当前请求是否为【外部用户】调用（而非服务间 Feign 直连）。
     * 外部请求经网关会携带 accToken，拦截器解析后向线程注入可信用户；
     * Feign 内部直连不带 accToken，线程无用户。据此区分，防止外部伪造 path userId 越权操作他人资产。
     *
     * @return true=外部用户调用
     */
    private boolean isExternalCall() {
        return AppThreadLocalUtil.getUser() != null;
    }

    /**
     * 获取用户资产（矿石余额）
     * 供其他服务 Feign 调用；外部用户禁止访问，防止越权读取他人生。
     */
    @GetMapping("/user/{userId}/assets")
    public ResponseResult getUserAssets(@PathVariable("userId") Long userId) {
        if (isExternalCall()) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        UserAssets assets = userAssetsMapper.selectById(userId);
        Map<String, Object> result = new HashMap<>();
        if (assets != null) {
            result.put("oreBalance", assets.getOreBalance() != null ? assets.getOreBalance() : 0);
            result.put("frozenOre", assets.getFrozenOre() != null ? assets.getFrozenOre() : 0);
            result.put("luckyValue", assets.getLuckyValue() != null ? assets.getLuckyValue() : 0);
        } else {
            result.put("oreBalance", 0);
            result.put("frozenOre", 0);
            result.put("luckyValue", 0);
        }
        return ResponseResult.okResult(result);
    }

    /**
     * 仅获取用户矿石余额（轻量接口）
     * 供其他服务 Feign 调用；外部用户禁止访问。
     */
    @GetMapping("/user/{userId}/ore")
    public ResponseResult getUserOreBalance(@PathVariable("userId") Long userId) {
        if (isExternalCall()) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        UserAssets assets = userAssetsMapper.selectById(userId);
        int oreBalance = (assets != null && assets.getOreBalance() != null) ? assets.getOreBalance() : 0;
        Map<String, Object> result = new HashMap<>();
        result.put("oreBalance", oreBalance);
        return ResponseResult.okResult(result);
    }

    /**
     * 增加用户矿石余额（用于等级奖励等场景）
     * 供其他服务 Feign 调用；外部用户禁止调用，防止任意给用户加矿造成资损。
     */
    @PostMapping("/user/{userId}/ore/add")
    public ResponseResult addOreBalance(@PathVariable("userId") Long userId,
                                         @RequestParam("amount") int amount) {
        if (isExternalCall()) {
            return ResponseResult.errorResult(403, "该接口仅限服务内部调用");
        }
        if (amount <= 0) {
            return ResponseResult.errorResult(400, "增加数量必须大于0");
        }
        userAssetsMapper.addOreBalance(userId, amount);
        UserAssets assets = userAssetsMapper.selectById(userId);
        int newBalance = (assets != null && assets.getOreBalance() != null) ? assets.getOreBalance() : amount;
        Map<String, Object> result = new HashMap<>();
        result.put("oreBalance", newBalance);
        result.put("added", amount);
        return ResponseResult.okResult(result);
    }
}
