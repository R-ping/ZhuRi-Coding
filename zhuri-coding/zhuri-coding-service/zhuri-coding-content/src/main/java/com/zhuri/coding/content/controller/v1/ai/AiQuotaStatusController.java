package com.zhuri.coding.content.controller.v1.ai;

import com.zhuri.coding.content.service.ai.AiQuotaService;
import com.zhuri.coding.content.service.ai.AiQuotaPackages;
import com.zhuri.coding.content.service.ai.AiWalletService;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
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
        // 统一 token 口径：原「次数」维度已下线（频次防刷由分层限流承担，成本只能由 token 表达），
        // 故不再返回 freeQuota / walletBalance 两个次数口径字段；前端只读 freeTokens / walletTokenBalance。
        Map<String, Object> freeTokens = new HashMap<>();
        freeTokens.put("dailyLimit", aiQuotaService.dailyTokenLimit());
        freeTokens.put("usedToday", aiQuotaService.tokensUsedToday(user.getId()));
        freeTokens.put("remainToday", aiQuotaService.tokensRemainToday(user.getId()));
        data.put("freeTokens", freeTokens);
        data.put("walletTokenBalance", walletService.tokenBalanceOf(user.getId()));

        Map<String, Object> packages = new LinkedHashMap<>();
        AiQuotaPackages.CATALOG.forEach((code, v) -> {
            Map<String, Object> p = new HashMap<>();
            p.put("quota", (int) v[0]);        // 次数（历史口径，类型保持 int 不变）
            p.put("priceFen", (int) v[1]);
            p.put("tokenQuota", v[2]);         // 新增：套餐到账 tokens（老前端忽略该字段即可）
            packages.put(code, p);
        });
        data.put("packages", packages);
        return ResponseResult.okResult(data);
    }
}
