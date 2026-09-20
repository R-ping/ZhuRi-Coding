package com.heima.reward.service;

import com.heima.model.common.dtos.ResponseResult;

import java.util.Map;

public interface WelfareService {
    /** 获取福利商品列表 */
    ResponseResult getGoodsList(Integer type, Integer page, Integer size);
    /** 获取商品详情 */
    ResponseResult getGoodsDetail(String goodsId);
    /** 执行兑换 */
    ResponseResult exchange(Long userId, Map<String, Object> body);
    /** 获取我的兑换记录 */
    ResponseResult getMyExchanges(Long userId, Integer page, Integer size, String status);
}
