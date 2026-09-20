package com.zhuri.coding.content.service.payment.impl;

import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.constants.LevelScoreConstants;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.course.ApCourseMapper;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.content.service.payment.PaymentRewardService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.course.pojos.ApCourse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 支付成功联动服务实现
 *
 * <p>支付回调确认成功后：
 * 1. 调用等级服务 {@link LevelService#recordPaymentAction} 按实际支付金额加逐日等级经验（金额即经验值）
 * 2. 调用站内信服务 {@link INotificationClient#sendActivityNotification} 发送"系统通知"
 *
 * <p>两个联动均做异常兜底，任一失败只记录日志，不影响支付主流程。
 */
@Slf4j
@Service
public class PaymentRewardServiceImpl implements PaymentRewardService {

    /** 支付行为类型（与 {@link LevelScoreConstants} 对齐） */
    private static final String ACTION_PURCHASE_COURSE = LevelScoreConstants.ACTION_PURCHASE_COURSE;
    private static final String ACTION_REWARD_ARTICLE = LevelScoreConstants.ACTION_REWARD_ARTICLE;

    @Autowired
    private LevelService levelService;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired
    private ApCourseMapper courseMapper;

    @Autowired
    private ApArticleMapper articleMapper;

    @Override
    public void onCoursePurchaseSuccess(Long userId, Long courseId, BigDecimal paidAmount, String orderNo) {
        // 1. 按实际支付金额给付款用户加逐日等级经验（受每日上限控制，失败不影响支付主流程）
        BigDecimal gained = grantDailyScore(userId, ACTION_PURCHASE_COURSE, paidAmount, "购买课程ID:" + courseId);
        // 2. 给付款用户发送"系统通知"站内信
        String courseTitle = "";
        try {
            ApCourse course = courseMapper.selectById(courseId);
            courseTitle = course != null && course.getTitle() != null ? course.getTitle() : "";
        } catch (Exception e) {
            log.warn("查询课程信息失败 courseId={}", courseId, e);
        }
        sendSystemNotification(userId, "课程购买成功",
            "您已成功购买课程《" + courseTitle + "》，实付 ¥" + formatAmount(paidAmount)
                + "，获得逐日等级经验 +" + formatAmount(gained) + "，快去开始学习吧！",
            "/course/" + courseId, orderNo);
    }

    @Override
    public void onArticleRewardSuccess(Long userId, Long articleId, BigDecimal amount, String orderNo) {
        // 1. 按实际支付金额给付款用户加逐日等级经验
        BigDecimal gained = grantDailyScore(userId, ACTION_REWARD_ARTICLE, amount, "打赏文章ID:" + articleId);
        // 2. 给付款用户发送"系统通知"站内信
        String articleTitle = "";
        try {
            ApArticle article = articleMapper.selectById(articleId);
            articleTitle = article != null && article.getTitle() != null ? article.getTitle() : "";
        } catch (Exception e) {
            log.warn("查询文章信息失败 articleId={}", articleId, e);
        }
        sendSystemNotification(userId, "打赏成功",
            "您已成功打赏文章《" + articleTitle + "》 ¥" + formatAmount(amount)
                + "，感谢您的支持！获得逐日等级经验 +" + formatAmount(gained) + "。",
            "/article/" + articleId, orderNo);
    }

    /**
     * 调用等级服务按金额给用户加逐日经验，返回实际获得经验值（受每日上限影响，可能为0）
     */
    private BigDecimal grantDailyScore(Long userId, String actionType, BigDecimal amount, String detail) {
        try {
            Map<String, Object> result = levelService.recordPaymentAction(userId, actionType, amount, detail);
            Object score = result != null ? result.get("score") : null;
            BigDecimal gained = score instanceof Number ? new BigDecimal(score.toString()) : BigDecimal.ZERO;
            log.info("支付成功加逐日等级经验: userId={}, actionType={}, amount={}, gained={}",
                userId, actionType, amount, gained);
            return gained;
        } catch (Exception e) {
            log.error("支付成功加逐日等级经验失败: userId={}, actionType={}", userId, actionType, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 调用站内信服务发送"系统通知"（type=4 system），失败不影响支付主流程
     */
    private void sendSystemNotification(Long userId, String title, String content, String link, String orderNo) {
        if (notificationClient == null) {
            log.warn("INotificationClient not available, skip system notification");
            return;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("title", title);
            params.put("content", content);
            params.put("link", link);
            ResponseResult result = notificationClient.sendActivityNotification(params);
            log.info("支付成功系统通知已发送: userId={}, orderNo={}, code={}",
                userId, orderNo, result != null ? result.getCode() : "null");
        } catch (Exception e) {
            log.error("支付成功系统通知发送失败: userId={}, orderNo={}", userId, orderNo, e);
        }
    }

    private String formatAmount(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00";
    }
}
