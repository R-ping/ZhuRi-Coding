package com.zhuri.coding.reward.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.reward.entity.*;
import com.zhuri.coding.reward.mapper.*;
import com.zhuri.coding.reward.service.WelfareService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WelfareServiceImpl implements WelfareService {

    /** 兑换码随机数：使用加密安全随机源，防止兑换码被预测 */
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    @Autowired
    private WelfareGoodsMapper goodsMapper;
    @Autowired
    private WelfareExchangeOrderMapper exchangeOrderMapper;
    @Autowired
    private WelfareStockLogMapper stockLogMapper;
    @Autowired
    private UserAssetsMapper userAssetsMapper;
    @Autowired
    private WelfareExchangeTxService exchangeTxService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public ResponseResult getGoodsList(Integer type, Integer page, Integer size) {
        if (page == null) page = 1;
        if (size == null) size = 20;
        if (type == null) type = 1;

        LambdaQueryWrapper<WelfareGoods> wrapper = new LambdaQueryWrapper<WelfareGoods>()
                .eq(WelfareGoods::getCategory, type)
                .eq(WelfareGoods::getStatus, 1)
                .orderByAsc(WelfareGoods::getSortOrder)
                .orderByDesc(WelfareGoods::getCreatedAt);

        Page<WelfareGoods> p = new Page<>(page, size);
        List<WelfareGoods> goodsList = goodsMapper.selectPage(p, wrapper).getRecords();

        List<Map<String, Object>> list = goodsList.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            m.put("goodsId", g.getId());
            m.put("name", g.getName());
            m.put("description", g.getDescription());
            m.put("imageUrl", g.getImageUrl());
            m.put("type", g.getType());
            m.put("orePrice", g.getOrePrice());
            m.put("originalPrice", g.getOriginalPrice());
            m.put("discountTag", g.getDiscountTag());
            m.put("stock", g.getStock());
            m.put("exchangedCount", g.getExchangedCount());
            m.put("status", g.getStock() != null && g.getStock() <= 0 ? 0 : 1);
            m.put("isTimeLimited", g.getTimeLimitStart() != null);
            m.put("timeLimitDesc", g.getTimeLimitDesc());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("size", size);

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getGoodsDetail(String goodsId) {
        WelfareGoods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            return ResponseResult.errorResult(400, "商品不存在");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("goodsId", goods.getId());
        data.put("name", goods.getName());
        data.put("description", goods.getDescription());
        data.put("imageUrl", goods.getImageUrl());
        data.put("type", goods.getType());
        data.put("orePrice", goods.getOrePrice());
        data.put("originalPrice", goods.getOriginalPrice());
        data.put("discountTag", goods.getDiscountTag());
        data.put("stock", goods.getStock());
        data.put("exchangedCount", goods.getExchangedCount());
        data.put("isVirtual", goods.getIsVirtual());
        data.put("status", goods.getStock() != null && goods.getStock() <= 0 ? 0 : 1);
        data.put("isTimeLimited", goods.getTimeLimitStart() != null);
        data.put("timeLimitDesc", goods.getTimeLimitDesc());
        data.put("exchangeNotice", "1. 虚拟商品兑换后即时发放，不可退款\n2. 实物商品将在15个工作日内发货\n3. 请确保收货地址正确，30天未填写地址将自动作废");

        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult exchange(Long userId, Map<String, Object> body) {
        if (userId == null || body == null) {
            return ResponseResult.errorResult(400, "缺少用户或参数");
        }
        String idempotencyKey = normalizeIdemKey(body.get("requestId"));

        if (idempotencyKey != null) {
            // 幂等命中：同一用户同一 requestId 直接回放原单，不再扣库存与矿石
            WelfareExchangeOrder existing = findByIdemKey(userId, idempotencyKey);
            if (existing != null) {
                log.info("兑换幂等命中，回放原单: userId={}, key={}", userId, idempotencyKey);
                return exchangeTxService.replayResult(userId, existing);
            }
        } else {
            // 没带幂等号时用 3 秒短锁挡连点：它只防重复提交，不承担库存一致性，
            // 所以 Redis 故障时放行（fail-open），正确性仍由 DB 条件更新与矿石条件扣减保证
            Boolean acquired = tryOnceLock("welfare:exchange:once:" + userId + ":" + body.get("goodsId"));
            if (Boolean.FALSE.equals(acquired)) {
                return ResponseResult.errorResult(400, "操作过于频繁，请稍后再试");
            }
        }

        try {
            return exchangeTxService.exchangeInTx(userId, body, idempotencyKey);
        } catch (DuplicateKeyException e) {
            // 并发同 key：唯一索引 uk_user_idem 只放一条，另一条在插入订单时冲突、整个事务已回滚；
            // 事务外回查并回放原单，用户侧表现为"两次请求返回同一张单"，不会重复扣
            WelfareExchangeOrder existing = findByIdemKey(userId, idempotencyKey);
            if (existing != null) {
                log.info("兑换并发幂等，回放已落地订单: userId={}, key={}", userId, idempotencyKey);
                return exchangeTxService.replayResult(userId, existing);
            }
            throw e;
        }
    }

    /** 幂等号规范化：去空白、限长 64（唯一索引列宽），空串视为未携带 */
    private static String normalizeIdemKey(Object raw) {
        if (raw == null) {
            return null;
        }
        String key = String.valueOf(raw).trim();
        if (key.isEmpty()) {
            return null;
        }
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    private WelfareExchangeOrder findByIdemKey(Long userId, String key) {
        if (key == null) {
            return null;
        }
        return exchangeOrderMapper.selectOne(new LambdaQueryWrapper<WelfareExchangeOrder>()
                .eq(WelfareExchangeOrder::getUserId, userId)
                .eq(WelfareExchangeOrder::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    /** 短窗口互斥锁；Redis 异常时放行（幂等短锁是体验优化，不是正确性闸） */
    private Boolean tryOnceLock(String key) {
        if (redisTemplate == null) {
            return Boolean.TRUE;
        }
        try {
            return redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(3));
        } catch (Exception e) {
            log.warn("兑换短锁不可用，放行本次请求: {}", e.getMessage());
            return Boolean.TRUE;
        }
    }

    @Override
    public ResponseResult getMyExchanges(Long userId, Integer page, Integer size, String status) {
        if (page == null) page = 1;
        if (size == null) size = 20;

        LambdaQueryWrapper<WelfareExchangeOrder> wrapper = new LambdaQueryWrapper<WelfareExchangeOrder>()
                .eq(WelfareExchangeOrder::getUserId, userId)
                .orderByDesc(WelfareExchangeOrder::getCreatedAt);

        if (status != null && !"all".equals(status)) {
            int statusVal = "pending".equals(status) ? 1 : "completed".equals(status) ? 2 : 3;
            wrapper.eq(WelfareExchangeOrder::getStatus, statusVal);
        }

        Page<WelfareExchangeOrder> p = new Page<>(page, size);
        List<WelfareExchangeOrder> orders = exchangeOrderMapper.selectPage(p, wrapper).getRecords();

        List<Map<String, Object>> list = orders.stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            m.put("exchangeId", o.getExchangeId());
            m.put("goodsName", o.getGoodsName());
            m.put("isVirtual", o.getIsVirtual());
            m.put("oreCost", o.getOreCost());
            m.put("status", o.getStatus());
            m.put("virtualCode", o.getVirtualCode());
            m.put("createdAt", DateUtil.formatDateTime(o.getCreatedAt()));

            if (o.getIsVirtual() != null && o.getIsVirtual()) {
                m.put("statusText", "已完成");
            } else {
                String statusText;
                switch (o.getStatus()) {
                    case 1: statusText = "待发货"; break;
                    case 2: statusText = "已发货"; break;
                    case 3: statusText = "已过期"; break;
                    default: statusText = "未知";
                }
                m.put("statusText", statusText);
            }
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("size", size);

        return ResponseResult.okResult(data);
    }
}
