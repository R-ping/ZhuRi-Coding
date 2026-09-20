package com.heima.content.controller.v1.order;

import com.heima.content.service.order.SettlementService;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/course/settlement")
public class SettlementController {

    @Autowired
    private SettlementService settlementService;

    /** 作者月度结算列表 */
    @GetMapping("/monthly")
    public ResponseResult getMonthlyList() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.NEED_LOGIN);
        }
        return settlementService.getMonthlyList(user.getId().longValue());
    }

    /** 结算明细 */
    @GetMapping("/detail")
    public ResponseResult getSettlementDetail(@RequestParam Long settlementId) {
        return settlementService.getSettlementDetail(settlementId);
    }
}