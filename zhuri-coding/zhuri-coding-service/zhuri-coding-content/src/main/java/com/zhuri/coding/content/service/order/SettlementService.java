package com.zhuri.coding.content.service.order;

import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface SettlementService {

    /** 作者月度结算列表 */
    ResponseResult getMonthlyList(Long authorId);

    /** 结算明细 */
    ResponseResult getSettlementDetail(Long settlementId);

    /** 触发月度结算（定时任务） */
    void executeMonthlySettlement(String month);
}