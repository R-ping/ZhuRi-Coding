package com.zhuri.coding.reward.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 用户虚拟道具（5折券等）服务
 */
public interface VirtualAssetService {

    /**
     * 入账：抽奖抽中虚拟道具时累加持有数量（内部调用，与抽奖同事务）
     *
     * @param userId   用户ID
     * @param itemCode 虚拟道具代码
     * @param itemName 道具名称
     * @param count    入账数量
     */
    void credit(Long userId, String itemCode, String itemName, int count);

    /**
     * 外部查询：用户当前持有的虚拟道具列表（"我的道具"分栏）
     *
     * @param userId 用户ID
     */
    ResponseResult listMyAssets(Long userId);

    /**
     * 内部查询：校验用户是否持有指定虚拟道具，并返回折扣比例（content 下单前校验）
     *
     * @param userId   用户ID
     * @param itemCode 虚拟道具代码
     */
    ResponseResult getHold(Long userId, String itemCode);

    /**
     * 内部核销：扣减用户持有的虚拟道具（content 支付成功后调用）
     *
     * @param userId   用户ID
     * @param itemCode 虚拟道具代码
     * @param count    核销数量
     */
    ResponseResult consume(Long userId, String itemCode, int count);
}