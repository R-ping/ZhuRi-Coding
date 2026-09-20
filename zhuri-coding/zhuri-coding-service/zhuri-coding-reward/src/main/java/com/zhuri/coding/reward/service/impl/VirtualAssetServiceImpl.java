package com.zhuri.coding.reward.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.LotteryPrizePool;
import com.zhuri.coding.reward.entity.UserVirtualAsset;
import com.zhuri.coding.reward.mapper.LotteryPrizePoolMapper;
import com.zhuri.coding.reward.mapper.UserVirtualAssetMapper;
import com.zhuri.coding.reward.service.VirtualAssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户虚拟道具服务实现
 * <p>
 * 入账：抽奖抽中虚拟道具时累加；核销：content 下单支付成功后扣减（全课程通用的5折券）。
 */
@Slf4j
@Service
public class VirtualAssetServiceImpl implements VirtualAssetService {

    @Autowired
    private UserVirtualAssetMapper virtualAssetMapper;

    @Autowired
    private LotteryPrizePoolMapper prizePoolMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void credit(Long userId, String itemCode, String itemName, int count) {
        if (userId == null || itemCode == null || itemCode.isBlank() || count <= 0) {
            return;
        }
        virtualAssetMapper.credit(userId, itemCode, itemName, count);
        log.info("虚拟道具入账: userId={}, itemCode={}, count={}", userId, itemCode, count);
    }

    @Override
    public ResponseResult listMyAssets(Long userId) {
        List<UserVirtualAsset> assets = virtualAssetMapper.selectList(
                new LambdaQueryWrapper<UserVirtualAsset>()
                        .eq(UserVirtualAsset::getUserId, userId)
                        .orderByAsc(UserVirtualAsset::getItemCode)
        );

        // 道具代码 → 奖品池配置，用于补充图标
        Map<String, LotteryPrizePool> configIndex = new HashMap<>();
        for (LotteryPrizePool p : prizePoolMapper.selectList(null)) {
            if (p.getVirtualItemCode() != null) {
                configIndex.putIfAbsent(p.getVirtualItemCode(), p);
            }
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (UserVirtualAsset a : assets) {
            if (a.getQuantity() == null || a.getQuantity() <= 0) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("itemCode", a.getItemCode());
            m.put("itemName", a.getItemName());
            m.put("quantity", a.getQuantity());
            LotteryPrizePool cfg = configIndex.get(a.getItemCode());
            m.put("iconUrl", cfg != null && cfg.getIconUrl() != null ? cfg.getIconUrl() : "");
            m.put("discountRate", cfg != null && cfg.getDiscountRate() != null
                    ? cfg.getDiscountRate().doubleValue() : 1.0d);
            list.add(m);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getHold(Long userId, String itemCode) {
        if (itemCode == null || itemCode.isBlank()) {
            return ResponseResult.errorResult(400, "缺少道具代码");
        }
        UserVirtualAsset asset = virtualAssetMapper.selectOne(
                new LambdaQueryWrapper<UserVirtualAsset>()
                        .eq(UserVirtualAsset::getUserId, userId)
                        .eq(UserVirtualAsset::getItemCode, itemCode)
        );

        LotteryPrizePool cfg = prizePoolMapper.selectOne(
                new LambdaQueryWrapper<LotteryPrizePool>()
                        .eq(LotteryPrizePool::getVirtualItemCode, itemCode)
                        .last("LIMIT 1")
        );

        Map<String, Object> data = new HashMap<>();
        int quantity = (asset != null && asset.getQuantity() != null) ? asset.getQuantity() : 0;
        data.put("itemCode", itemCode);
        data.put("itemName", asset != null ? asset.getItemName() : (cfg != null ? cfg.getName() : ""));
        data.put("quantity", quantity);
        data.put("discountRate", cfg != null && cfg.getDiscountRate() != null
                ? cfg.getDiscountRate().doubleValue() : 1.0d);
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult consume(Long userId, String itemCode, int count) {
        if (itemCode == null || itemCode.isBlank()) {
            return ResponseResult.errorResult(400, "缺少道具代码");
        }
        if (count <= 0) {
            return ResponseResult.errorResult(400, "核销数量必须大于0");
        }
        int affected = virtualAssetMapper.consume(userId, itemCode, count);
        if (affected <= 0) {
            return ResponseResult.errorResult(400, "道具不存在或数量不足");
        }
        log.info("虚拟道具核销: userId={}, itemCode={}, count={}", userId, itemCode, count);
        UserVirtualAsset asset = virtualAssetMapper.selectOne(
                new LambdaQueryWrapper<UserVirtualAsset>()
                        .eq(UserVirtualAsset::getUserId, userId)
                        .eq(UserVirtualAsset::getItemCode, itemCode)
        );
        Map<String, Object> data = new HashMap<>();
        data.put("quantity", asset != null && asset.getQuantity() != null ? asset.getQuantity() : 0);
        return ResponseResult.okResult(data);
    }
}