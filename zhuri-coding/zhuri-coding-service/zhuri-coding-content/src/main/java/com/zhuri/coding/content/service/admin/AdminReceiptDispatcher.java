package com.zhuri.coding.content.service.admin;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.common.constants.ArticleConstants;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleReportMapper;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessage;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApArticleReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 举报处置的两条站内信投递（举报人回执 / 作者违规告知）。
 *
 * <p><b>为什么也走本地消息表</b>：举报闭环的"闭"就闭在回执上 ——
 * 用户举报完等几天，什么消息都没有，下次就不举报了。通知服务抖动一次就丢掉回执，
 * 等于这次的治理白做。走 {@link LocalMessage} 让它随下架动作同事务登记、失败自动重试。
 *
 * <p><b>为什么重放方法不加 @Transactional</b>：方法体是「一次跨服务调用 + 一条单语句 UPDATE」。
 * 单条 UPDATE 自身即原子，不需要事务包裹；而加上事务会让人误以为两件事能一起回滚，
 * 实际上通知已经发出去了、回滚不了。宁可写明白：通知可能重复，但通知丢失不可接受。
 *
 * <p>重复投递的代价是用户可能收到两条同样的回执，可以被接受；这也是重试的前提 ——
 * 若消息本身不幂等，就不能自动重试。
 */
@Slf4j
@Component
public class AdminReceiptDispatcher {

    /** 事件类型：回执给举报人 */
    public static final String EVENT_REPORT_RECEIPT = "ADMIN_REPORT_RECEIPT";
    /** 事件类型：违规告知作者 */
    public static final String EVENT_AUTHOR_NOTIFY = "ADMIN_REPORT_AUTHOR_NOTIFY";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private INotificationClient notificationClient;

    @Autowired
    private ApArticleReportMapper reportMapper;

    @Autowired
    private ApArticleMapper articleMapper;

    /**
     * 给举报人发处置回执。
     *
     * <p>回执必须包含"为什么这么判"（{@code handleReason}）——只回一句"已处理"，
     * 举报人不知道是自己看错了还是平台真的处理了，下次就不会再举报。
     *
     * @param reportId 举报记录ID
     */
    @LocalMessage(eventType = EVENT_REPORT_RECEIPT, key = "'admin_report_receipt:' + #a0")
    public void deliverReportReceipt(Long reportId) {
        ApArticleReport report = reportMapper.selectById(reportId);
        if (report == null || report.getUserId() == null) {
            log.warn("[AdminReport] 回执跳过：举报记录不存在或举报人缺失, reportId={}", reportId);
            return;
        }
        String title = articleTitleOf(report.getArticleId());
        String message = receiptText(title, report.getHandleResult(), report.getHandleReason());

        try {
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("reportId", String.valueOf(reportId));
            contentMap.put("articleId", String.valueOf(report.getArticleId()));
            contentMap.put("articleTitle", title);
            contentMap.put("handleResult", report.getHandleResult());
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", report.getUserId().longValue());
            params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
            params.put("sourceId", String.valueOf(reportId));
            params.put("content", JSON.writeValueAsString(contentMap));

            notificationClient.createNotification(params);
            markNotify(reportId, ApArticleReport.NOTIFY_SENT);
            log.info("[AdminReport] 举报回执已发送, reportId={}, to={}", reportId, report.getUserId());
        } catch (Exception e) {
            // 不吞：交给本地消息表重试。notify_status 先标失败，便于对账时看出"卡在这一步"
            markNotify(reportId, ApArticleReport.NOTIFY_FAILED);
            throw new IllegalStateException("举报回执发送失败, reportId=" + reportId, e);
        }
    }

    /**
     * 告知作者其内容被举报后的处置结果（仅「警告作者」与「下架内容」两种结论需要）。
     *
     * <p>参数按值传入而不是重放时回查举报记录：作者通知是**文章级**的一次性动作，
     * 同一下架动作可能结了十几条举报，逐条回查只会把同一封信发十几遍。
     *
     * @param articleId    文章ID
     * @param authorId     作者账号ID
     * @param handleResult 处置结论（1驳回 2警告 3下架）
     * @param reason       处置说明（原样告知作者）
     */
    @LocalMessage(eventType = EVENT_AUTHOR_NOTIFY, key = "'admin_report_author_notify:' + #a0 + ':' + #a2")
    public void notifyAuthorOfHandling(Long articleId, Long authorId, Integer handleResult, String reason) {
        if (authorId == null) {
            log.warn("[AdminReport] 作者告知跳过：作者ID缺失, articleId={}", articleId);
            return;
        }
        String title = articleTitleOf(articleId);
        String message = authorText(title, handleResult, reason);

        Map<String, Object> contentMap = new HashMap<>();
        contentMap.put("articleId", String.valueOf(articleId));
        contentMap.put("articleTitle", title);
        contentMap.put("handleResult", handleResult);
        contentMap.put("reason", reason);
        contentMap.put("message", message);
        contentMap.put("notification_type", "system");
        // 供前端区分展示与跳转：被平台下架的内容可以走申诉入口
        contentMap.put("adminHandleType", handleResult);

        Map<String, Object> params = new HashMap<>();
        params.put("userId", authorId);
        params.put("type", ArticleConstants.NOTIFICATION_TYPE_SYSTEM);
        params.put("sourceId", String.valueOf(articleId));
        try {
            params.put("content", JSON.writeValueAsString(contentMap));
        } catch (Exception e) {
            throw new IllegalStateException("作者告知内容序列化失败, articleId=" + articleId, e);
        }

        notificationClient.createNotification(params);
        log.info("[AdminReport] 处置结果已告知作者, articleId={}, authorId={}, result={}",
            articleId, authorId, handleResult);
    }

    /** 回复举报人的文案 */
    private String receiptText(String title, Integer handleResult, String handleReason) {
        String conclusion;
        if (handleResult != null && handleResult == ApArticleReport.RESULT_TAKE_DOWN) {
            conclusion = "经核实存在违规，内容已被下架。";
        } else if (handleResult != null && handleResult == ApArticleReport.RESULT_WARN_AUTHOR) {
            conclusion = "经核实存在违规，我们已向作者发出警告。";
        } else {
            conclusion = "经核实未发现违规，本次不做处理。";
        }
        String detail = handleReason != null && !handleReason.isBlank() ? "处理说明：" + handleReason : "";
        return "你举报的《" + title + "》" + conclusion + detail + "感谢你对社区的支持。";
    }

    /** 告知作者的文案 */
    private String authorText(String title, Integer handleResult, String reason) {
        String detail = reason != null && !reason.isBlank() ? reason : "违反社区规范";
        if (handleResult != null && handleResult == ApArticleReport.RESULT_TAKE_DOWN) {
            return "你的文章《" + title + "》经举报核实存在违规，已被平台下架。原因：" + detail
                + "。如有异议可在内容治理入口提交申诉。";
        }
        return "你的文章《" + title + "》被举报并经核实存在违规，本次仅作警告，内容未被下架。原因：" + detail
            + "。请及时修改，再次违规将下架内容。";
    }

    /** 取文章标题；文章缺失时给一个可读兜底，不让通知文案出现"null" */
    private String articleTitleOf(Long articleId) {
        if (articleId == null) {
            return "无标题";
        }
        ApArticle article = articleMapper.selectById(articleId);
        if (article == null || article.getTitle() == null || article.getTitle().isBlank()) {
            return "无标题";
        }
        return article.getTitle();
    }

    /** 回写回执发送状态（单语句更新，原子） */
    private void markNotify(Long reportId, int notifyStatus) {
        try {
            reportMapper.update(null, new LambdaUpdateWrapper<ApArticleReport>()
                .set(ApArticleReport::getNotifyStatus, notifyStatus)
                .eq(ApArticleReport::getId, reportId));
        } catch (Exception e) {
            // 状态回写失败不影响回执本身，只记日志
            log.warn("[AdminReport] 回执状态回写失败, reportId={}, status={}", reportId, notifyStatus, e);
        }
    }
}
