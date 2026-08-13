package com.heima.content.service.tip.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.tip.ApArticleTipOrderMapper;
import com.heima.content.mapper.tip.ApArticleTipRecordMapper;
import com.heima.content.service.pay.AlipayService;
import com.heima.content.service.payment.PaymentRewardService;
import com.heima.content.service.tip.TipService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleTipOrder;
import com.heima.model.article.pojos.ApArticleTipRecord;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文章打赏服务实现类
 *
 * <p>打赏金额进入平台账户，作者可凭后台流水与平台结算；同时公开展示打赏感谢名单。
 */
@Service
@Slf4j
public class TipServiceImpl implements TipService {

    @Autowired
    private ApArticleTipOrderMapper tipOrderMapper;

    @Autowired
    private ApArticleTipRecordMapper tipRecordMapper;

    @Autowired
    private ApArticleMapper articleMapper;

    @Autowired
    private AlipayService alipayService;

    @Autowired
    private IUserClient userClient;

    @Autowired
    private PaymentRewardService paymentRewardService;

    /** 打赏金额范围（元） */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("1");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000");

    /** 对外网关地址前缀，用于拼装支付宝绝对通知/回跳地址 */
    @Value("${alipay.base-url:http://localhost:51601}")
    private String payBaseUrl;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult createOrder(Long articleId, BigDecimal amount, String message, Long userId) {
        // 参数校验
        if (articleId == null || userId == null || amount == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "打赏金额需在 1~10000 元之间");
        }

        // 查询文章
        ApArticle article = articleMapper.selectById(articleId);
        if (article == null || article.isDeletedArticle()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章不存在");
        }
        if (article.getAuthorId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "文章作者不存在");
        }
        // 不能打赏自己的文章
        if (article.getAuthorId().intValue() == userId.intValue()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不能打赏自己的文章");
        }

        // 创建订单
        ApArticleTipOrder order = new ApArticleTipOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId.intValue());
        order.setArticleId(articleId);
        order.setAuthorId(article.getAuthorId().intValue());
        order.setAmount(amount);
        order.setMessage(message != null ? message : "");
        order.setStatus(ApArticleTipOrder.Status.PENDING.getCode());
        order.setCreatedTime(new Date());
        order.setUpdatedTime(new Date());
        tipOrderMapper.insert(order);

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("amount", amount);
        result.put("payUrl", "/content/api/v1/tip/pay/page?orderNo=" + order.getOrderNo());
        return ResponseResult.okResult(result);
    }

    @Override
    public String getPayPage(String orderNo) {
        ApArticleTipOrder order = getByOrderNo(orderNo);
        if (order == null) {
            return "<html><body><h2>订单不存在</h2></body></html>";
        }
        if (order.getStatus() != ApArticleTipOrder.Status.PENDING.getCode()) {
            return "<html><body><h2>订单状态异常</h2></body></html>";
        }
        String subject = "文章打赏 - " + order.getArticleId();
        // 支付宝通知/回跳地址必须是绝对、外网可访问的地址；本地联调用网关前缀，
        // 生产环境通过 ALIPAY_BASE_URL 注入对外网关地址（如 https://域名）。
        String notifyUrl = payBaseUrl + "/content/api/v1/tip/notify";
        String returnUrl = payBaseUrl + "/content/article/" + order.getArticleId() + "?tip=success&orderNo=" + order.getOrderNo();
        return alipayService.generatePayPage(orderNo, subject, order.getAmount().toString(), notifyUrl, returnUrl);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleNotify(String tradeNo, String orderNo, String totalAmount, String status) {
        if (!"TRADE_SUCCESS".equals(status)) {
            log.warn("打赏支付状态非成功: {}", status);
            return false;
        }

        ApArticleTipOrder order = getByOrderNo(orderNo);
        if (order == null) {
            log.error("打赏订单不存在: {}", orderNo);
            return false;
        }
        if (order.getStatus() != ApArticleTipOrder.Status.PENDING.getCode()) {
            log.warn("打赏订单状态异常: {}, status={}", orderNo, order.getStatus());
            return true;
        }

        // 1. 更新订单为已支付
        order.setStatus(ApArticleTipOrder.Status.PAID.getCode());
        order.setTradeNo(tradeNo);
        order.setPayTime(new Date());
        order.setUpdatedTime(new Date());
        tipOrderMapper.updateById(order);

        // 2. 写入打赏流水（公开感谢名单），冗余打赏人昵称与头像
        ApArticleTipRecord record = new ApArticleTipRecord();
        record.setOrderNo(order.getOrderNo());
        record.setUserId(order.getUserId());
        record.setArticleId(order.getArticleId());
        record.setAuthorId(order.getAuthorId());
        record.setAmount(order.getAmount());
        record.setMessage(order.getMessage());
        record.setCreatedTime(new Date());
        // 从用户服务获取打赏人昵称与头像
        String nickName = "";
        String avatar = "";
        try {
            ResponseResult userResult = userClient.getBasicInfo(order.getUserId().longValue());
            if (userResult != null && userResult.getCode() == 200 && userResult.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> userData = (Map<String, Object>) userResult.getData();
                nickName = str(userData.get("nickname"));
                avatar = str(userData.get("avatar"));
            }
        } catch (Exception e) {
            log.warn("获取打赏人信息失败, userId={}", order.getUserId(), e);
        }
        record.setNickName(nickName);
        record.setAvatar(avatar);
        tipRecordMapper.insert(record);

        // 3. 更新文章打赏汇总
        articleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                .eq(ApArticle::getId, order.getArticleId())
                .setSql("tip_count = tip_count + 1")
                .setSql("tip_amount = tip_amount + " + order.getAmount()));

        // 4. 支付成功联动：加逐日等级经验 + 发"系统通知"站内信（失败不影响支付主流程）
        try {
            paymentRewardService.onArticleRewardSuccess(order.getUserId().longValue(),
                order.getArticleId(), order.getAmount(), order.getOrderNo());
        } catch (Exception e) {
            log.error("打赏支付成功联动失败: orderNo={}", orderNo, e);
        }

        log.info("文章打赏成功: orderNo={}, tradeNo={}, userId={}, articleId={}, amount={}",
                orderNo, tradeNo, order.getUserId(), order.getArticleId(), order.getAmount());
        return true;
    }

    @Override
    public ResponseResult getTipSummary(Long articleId) {
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        ApArticle article = articleMapper.selectById(articleId);
        Map<String, Object> result = new HashMap<>();
        result.put("tipCount", article != null && article.getTipCount() != null ? article.getTipCount() : 0);
        result.put("tipAmount", article != null && article.getTipAmount() != null ? article.getTipAmount() : BigDecimal.ZERO);
        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult getTipList(Long articleId, Integer page, Integer size) {
        if (articleId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 50);

        IPage<ApArticleTipRecord> iPage = new Page<>(p, s);
        LambdaQueryWrapper<ApArticleTipRecord> query = new LambdaQueryWrapper<>();
        query.eq(ApArticleTipRecord::getArticleId, articleId);
        query.orderByDesc(ApArticleTipRecord::getCreatedTime);
        IPage<ApArticleTipRecord> resultPage = tipRecordMapper.selectPage(iPage, query);

        Map<String, Object> data = new HashMap<>();
        data.put("list", resultPage.getRecords());
        data.put("total", resultPage.getTotal());
        data.put("page", resultPage.getCurrent());
        data.put("size", resultPage.getSize());
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getMyRevenue(Long authorId) {
        if (authorId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        LambdaQueryWrapper<ApArticleTipOrder> query = new LambdaQueryWrapper<>();
        query.eq(ApArticleTipOrder::getAuthorId, authorId.intValue());
        query.eq(ApArticleTipOrder::getStatus, ApArticleTipOrder.Status.PAID.getCode());
        query.orderByDesc(ApArticleTipOrder::getPayTime);
        List<ApArticleTipOrder> orders = tipOrderMapper.selectList(query);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ApArticleTipOrder o : orders) {
            if (o.getAmount() != null) {
                totalAmount = totalAmount.add(o.getAmount());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalAmount", totalAmount);
        result.put("totalCount", orders.size());
        result.put("list", orders);
        return ResponseResult.okResult(result);
    }

    private ApArticleTipOrder getByOrderNo(String orderNo) {
        LambdaQueryWrapper<ApArticleTipOrder> query = new LambdaQueryWrapper<>();
        query.eq(ApArticleTipOrder::getOrderNo, orderNo);
        return tipOrderMapper.selectOne(query);
    }

    private String generateOrderNo() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        return "T" + sdf.format(new Date()) + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
