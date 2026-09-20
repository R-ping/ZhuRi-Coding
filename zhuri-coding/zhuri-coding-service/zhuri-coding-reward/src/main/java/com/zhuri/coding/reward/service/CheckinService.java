package com.zhuri.coding.reward.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface CheckinService {
    /** 获取签到状态与日历数据（新接口） */
    ResponseResult getStatus(Long userId);

    /** 执行每日签到 */
    ResponseResult doCheckin(Long userId);

    /** 执行补签操作 */
    ResponseResult doExtra(Long userId, String targetDate);

    /** 获取今日签到状态（侧边栏用） */
    ResponseResult getTodayStatus(Long userId);

    /** 获取用户连续签到天数（含今日，供其他服务 Feign 调用） */
    ResponseResult getContinuousCheckinDays(Long userId);
}