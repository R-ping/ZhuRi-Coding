package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.heima.content.mapper.ai.AiTopupOrderMapper;
import com.heima.content.service.ai.AiQuotaPackages;
import com.heima.content.service.ai.AiTopupService;
import com.heima.content.service.ai.AiWalletService;
import com.heima.content.service.pay.AlipayService;
import com.heima.model.ai.pojos.AiTopupOrder;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 额度包充值实现
 */
@Slf4j
@Service
public class AiTopupServiceImpl implements AiTopupService {

    /** 回调地址（与课程订单共用 PayController /notify，按前缀分发） */
    private static final String NOTIFY_PATH = "/content/api/v1/course/pay/notify";

    @Autowired
    private AiTopupOrderMapper topupOrderMapper;

    @Autowired
    private AiWalletService walletService;

    @Autowired
    private AlipayService alipayService;

    @Value("${alipay.base-url:http://localhost:51601}")
    private String payBaseUrl;

    @Value("${alipay.web-base-url:http://localhost:9901}")
    private String webBaseUrl;

    @Override
    public ResponseResult createTopup(Integer userId, String packageCode) {
        if (!AiQuotaPackages.isValid(packageCode)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的额度包");
        }
        String orderNo = AiTopupOrder.ORDER_PREFIX + IdWorker.getId();
        AiTopupOrder order = new AiTopupOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setPackageCode(packageCode);
        order.setAmountFen(AiQuotaPackages.priceFenOf(packageCode));
        order.setQuotaAdded(AiQuotaPackages.quotaOf(packageCode));
        order.setTokenAdded(AiQuotaPackages.tokenQuotaOf(packageCode));
        order.setStatus(AiTopupOrder.STATUS_PENDING);
        order.setPayTradeNo("");
        order.setCreateTime(new Date());
        order.setUpdateTime(new Date());
        topupOrderMapper.insert(order);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("packageCode", packageCode);
        data.put("quota", order.getQuotaAdded());
        data.put("amountFen", order.getAmountFen());
        log.info("AI 额度包订单已创建, userId={}, orderNo={}, code={}", userId, orderNo, packageCode);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult buildPayPage(String orderNo, Integer userId) {
        AiTopupOrder order = findOwnOrder(orderNo, userId);
        if (order == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "订单不存在");
        }
        if (order.getStatus() != AiTopupOrder.STATUS_PENDING) {
            return ResponseResult.errorResult(500, "订单不在待支付状态");
        }
        String amount = yuan(order.getAmountFen());
        String subject = "AI 问答额度包 ×" + order.getQuotaAdded() + " 次";
        String html = alipayService.generatePayPage(
            order.getOrderNo(), subject, amount, payBaseUrl + NOTIFY_PATH, webBaseUrl + "/");
        Map<String, Object> data = new HashMap<>();
        data.put("html", html);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getStatus(String orderNo, Integer userId) {
        AiTopupOrder order = findOwnOrder(orderNo, userId);
        if (order == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "订单不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("packageCode", order.getPackageCode());
        data.put("amountFen", order.getAmountFen());
        data.put("quotaAdded", order.getQuotaAdded());
        data.put("status", order.getStatus());
        data.put("payTradeNo", order.getPayTradeNo());
        data.put("createTime", order.getCreateTime());
        return ResponseResult.okResult(data);
    }

    @Override
    public boolean handleNotify(String orderNo, String totalAmount, String tradeNo) {
        AiTopupOrder order = topupOrderMapper.selectOne(new LambdaQueryWrapper<AiTopupOrder>()
            .eq(AiTopupOrder::getOrderNo, orderNo).last("LIMIT 1"));
        if (order == null) {
            log.error("[AiTopup] 回调订单不存在, orderNo={}", orderNo);
            return false;
        }
        // 幂等：已入账的重复回调直接确认成功
        if (order.getStatus() != null && order.getStatus() == AiTopupOrder.STATUS_PAID) {
            return true;
        }
        if (order.getStatus() == null || order.getStatus() != AiTopupOrder.STATUS_PENDING) {
            log.warn("[AiTopup] 订单非待支付态，忽略回调, orderNo={}, status={}", orderNo, order.getStatus());
            return true;
        }
        // 金额一致性：仅以服务端订单金额为准，防回调金额篡改
        String expect = yuan(order.getAmountFen());
        if (totalAmount == null || new BigDecimal(totalAmount).setScale(2, RoundingMode.HALF_UP)
            .compareTo(new BigDecimal(expect)) != 0) {
            log.error("[AiTopup] 回调金额不一致, orderNo={}, 回调={}, 期望={}", orderNo, totalAmount, expect);
            return false;
        }
        order.setStatus(AiTopupOrder.STATUS_PAID);
        order.setPayTradeNo(tradeNo == null ? "" : tradeNo);
        order.setPayTime(new Date());
        order.setUpdateTime(new Date());
        topupOrderMapper.updateById(order);
        // 入账钱包（支付成功后才给额度）
        walletService.grant(order.getUserId(), order.getQuotaAdded());
        // token 额度双写（新计费口径）：用量按 token 结算，次数仅作兼容展示
        if (order.getTokenAdded() != null && order.getTokenAdded() > 0) {
            walletService.grantTokens(order.getUserId(), order.getTokenAdded());
        }
        log.info("[AiTopup] 额度包支付成功并入账, orderNo={}, userId={}, +{} 次, +{} tokens",
            orderNo, order.getUserId(), order.getQuotaAdded(), order.getTokenAdded());
        return true;
    }

    private AiTopupOrder findOwnOrder(String orderNo, Integer userId) {
        if (orderNo == null || userId == null) {
            return null;
        }
        AiTopupOrder order = topupOrderMapper.selectOne(new LambdaQueryWrapper<AiTopupOrder>()
            .eq(AiTopupOrder::getOrderNo, orderNo).last("LIMIT 1"));
        return order != null && order.getUserId() != null && order.getUserId().equals(userId) ? order : null;
    }

    /** 分 → 元字符串（支付宝金额两位小数） */
    private static String yuan(Integer fen) {
        return fen == null ? "0.00"
            : new BigDecimal(fen).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP).toPlainString();
    }
}
