package com.heima.content.controller.v1.ai;

import com.heima.content.service.ai.AiQuotaService;
import com.heima.content.service.ai.AiQuotaPackages;
import com.heima.content.service.ai.AiWalletService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 配额状态端点：今日免费剩余 / 钱包余额 / 可购额度包目录
 */
@RestController
@RequestMapping("/api/v1/ai/quota")
public class AiQuotaStatusController {

    @Autowired
    private AiQuotaService aiQuotaService;

    @Autowired
    private AiWalletService walletService;

    @GetMapping("/status")
    public ResponseResult status() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> free = new HashMap<>();
        free.put("dailyLimit", AiQuotaService.DAILY_QUOTA);
        free.put("usedToday", aiQuotaService.usedToday(user.getId()));
        free.put("remainToday", aiQuotaService.remainToday(user.getId()));
        data.put("freeQuota", free);
        data.put("walletBalance", walletService.balanceOf(user.getId()));
        Map<String, Object> packages = new LinkedHashMap<>();
        AiQuotaPackages.CATALOG.forEach((code, v) -> {
            Map<String, Object> p = new HashMap<>();
            p.put("quota", v[0]);
            p.put("priceFen", v[1]);
            packages.put(code, p);
        });
        data.put("packages", packages);
        return ResponseResult.okResult(data);
    }
}
