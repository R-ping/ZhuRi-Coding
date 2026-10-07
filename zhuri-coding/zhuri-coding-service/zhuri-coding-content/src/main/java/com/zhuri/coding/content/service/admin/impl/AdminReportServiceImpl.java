package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.common.admin.AdminContext;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleReportMapper;
import com.zhuri.coding.content.service.admin.AdminReceiptDispatcher;
import com.zhuri.coding.content.service.admin.AdminReportService;
import com.zhuri.coding.content.service.admin.ArticleIndexRemover;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.ArticleReportVO;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApArticleReport;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 运营侧举报处置实现。
 *
 * <p><b>下架动作为什么不能复用作者删除</b>：{@code ArticleManageServiceImpl#deleteArticle} 会
 * 硬校验"文章必须是我自己写的"（运营不是作者，调用必然失败），且它只置 {@code is_deleted}。
 * {@code is_deleted} 会把文章从作者自己的作品列表里也一并过滤掉 —— 作者连"我的文章去哪了、
 * 为什么没了"都看不到，也就无从申诉。所以平台下架走独立状态 {@code Status.TAKEN_DOWN}：
 * 线上（首页/详情/搜索/推荐）不可见，作者列表仍在、并附处置原因。
 */
@Slf4j
@Service
public class AdminReportServiceImpl implements AdminReportService {

    /** 单页上限：队列页不需要一次拉几百条，拦住前端误传 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Autowired
    private ApArticleReportMapper reportMapper;

    @Autowired
    private ApArticleMapper articleMapper;

    @Autowired
    private ArticleIndexRemover articleIndexRemover;

    @Autowired
    private AdminReceiptDispatcher receiptDispatcher;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    /** 向量清理是本地 PG 操作，直接注入实现类（删除方法是该类自有、未在接口上暴露） */
    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Override
    public ResponseResult page(Integer status, Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;

        LambdaQueryWrapper<ApArticleReport> wrapper = new LambdaQueryWrapper<ApArticleReport>()
            // 显式投影：imageUrls 之外的说明字段都要，但刻意点全，避免以后加了大字段被顺手带出来
            .select(ApArticleReport::getId, ApArticleReport::getArticleId, ApArticleReport::getAuthorId,
                ApArticleReport::getUserId, ApArticleReport::getReason, ApArticleReport::getDescription,
                ApArticleReport::getImageUrls, ApArticleReport::getStatus, ApArticleReport::getHandleResult,
                ApArticleReport::getHandleReason, ApArticleReport::getHandlerId, ApArticleReport::getHandleTime,
                ApArticleReport::getNotifyStatus, ApArticleReport::getCreatedTime)
            // 待处理(0)排在最前，同组内按 id 升序 = 先报先办，避免老举报一直沉底
            .orderByAsc(ApArticleReport::getStatus)
            .orderByAsc(ApArticleReport::getId);
        if (status != null) {
            wrapper.eq(ApArticleReport::getStatus, status);
        }

        IPage<ApArticleReport> result = reportMapper.selectPage(new Page<>(p, s), wrapper);
        Map<Long, ApArticle> articles = loadArticles(result.getRecords());
        Map<Long, Integer> pendingCounts = loadPendingCounts(result.getRecords());

        List<ArticleReportVO> list = result.getRecords().stream().map(r -> {
            ArticleReportVO vo = new ArticleReportVO();
            vo.setId(r.getId());
            vo.setArticleId(r.getArticleId());
            vo.setAuthorId(r.getAuthorId());
            vo.setUserId(r.getUserId());
            vo.setReason(r.getReason());
            vo.setDescription(r.getDescription());
            vo.setImageUrls(splitImages(r.getImageUrls()));
            vo.setStatus(r.getStatus());
            vo.setHandleResult(r.getHandleResult());
            vo.setHandleReason(r.getHandleReason());
            vo.setHandlerId(r.getHandlerId());
            vo.setHandleTime(r.getHandleTime());
            vo.setNotifyStatus(r.getNotifyStatus());
            vo.setCreatedTime(r.getCreatedTime());

            ApArticle article = r.getArticleId() == null ? null : articles.get(r.getArticleId());
            if (article != null) {
                vo.setArticleTitle(article.getTitle());
                vo.setArticleStatus(article.getStatus());
            }
            vo.setPendingCountOfArticle(pendingCounts.getOrDefault(r.getArticleId(), 0));
            return vo;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", result.getTotal());
        data.put("page", p);
        data.put("size", s);
        return ResponseResult.okResult(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult handle(Long reportId, Integer handleResult, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(
            ApAdminAuditLog.MODULE_REPORT, actionOf(handleResult), "REPORT",
            String.valueOf(reportId), reason);
        try {
            return doHandle(reportId, handleResult, reason, audit);
        } catch (Exception e) {
            // 失败也留痕。此处业务事务已被标记回滚，记录走独立事务（见 AdminAuditRecorder#recordFailure），
            // 所以这条记录不会跟着一起消失。
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doHandle(Long reportId, Integer handleResult, String reason, ApAdminAuditLog audit) {
        ApArticleReport report = reportMapper.selectById(reportId);
        if (report == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "举报记录不存在");
        }

        // 下架是比"处置举报"更重的动作，权限点在注解之上再叠一层：
        // 驳回/警告是可逆判断，把已发布内容从线上撤下来会直接影响作者与读者。
        // 这里记一条失败审计 —— 注解层（AdminAuthInterceptor）只挡得住整接口级别的越权，
        // "有处置权但没有下架权"这种接口内部的越权只有在这里才看得到。
        if (handleResult != null && handleResult == ApArticleReport.RESULT_TAKE_DOWN && !canTakeDown()) {
            log.warn("[AdminReport] 下架未授权被拒, reportId={}, operator={}", reportId, currentOperatorId());
            auditRecorder.recordFailure(audit, "无权限下架内容");
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权限下架内容");
        }

        Date now = new Date();
        Integer operatorId = currentOperatorId();

        // ① 认领：条件更新只对 status=0 生效。两个运营同时点"下架"，只有一个能改到这一行，
        //    另一个 affected=0 → 明确告知已处置。这就是本方法的幂等闸口。
        int claimed = reportMapper.update(null, concludedUpdate(handleResult, reason, operatorId, now)
            .eq(ApArticleReport::getId, reportId)
            .eq(ApArticleReport::getStatus, ApArticleReport.STATUS_PENDING));
        if (claimed == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "该举报已处置，请刷新队列");
        }

        // ② 同文章的其余待处理举报一并结案（结案粒度是文章，不是单条举报）
        List<Long> siblingIds = reportMapper.selectList(new LambdaQueryWrapper<ApArticleReport>()
                .select(ApArticleReport::getId)
                .eq(ApArticleReport::getArticleId, report.getArticleId())
                .eq(ApArticleReport::getStatus, ApArticleReport.STATUS_PENDING))
            .stream().map(ApArticleReport::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (!siblingIds.isEmpty()) {
            reportMapper.update(null, concludedUpdate(handleResult, reason, operatorId, now)
                .in(ApArticleReport::getId, siblingIds)
                .eq(ApArticleReport::getStatus, ApArticleReport.STATUS_PENDING));
        }

        // ③ 按结论执行副作用
        boolean takenDown = false;
        if (handleResult != null && handleResult == ApArticleReport.RESULT_TAKE_DOWN) {
            takenDown = takeDown(report.getArticleId(), reason, now);
        }

        // ④ 回执：每条结案举报的举报人各收一份（登记进本地消息表，提交后投递）
        List<Long> concludedIds = new ArrayList<>();
        concludedIds.add(reportId);
        concludedIds.addAll(siblingIds);
        for (Long id : concludedIds) {
            receiptDispatcher.deliverReportReceipt(id);
        }

        // ⑤ 告知作者：只有"警告"与"下架"才对作者有意义；驳回等于什么都没发生，不打扰
        if (handleResult != null && (handleResult == ApArticleReport.RESULT_WARN_AUTHOR
            || handleResult == ApArticleReport.RESULT_TAKE_DOWN)) {
            receiptDispatcher.notifyAuthorOfHandling(
                report.getArticleId(), report.getAuthorId(), handleResult, reason);
        }

        audit.setDetail(String.format("articleId=%s, concluded=%d, takenDown=%s",
            report.getArticleId(), concludedIds.size(), takenDown));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminReport] 举报处置完成, reportId={}, result={}, 结案{}条, 下架={}",
            reportId, handleResult, concludedIds.size(), takenDown);

        Map<String, Object> data = new HashMap<>();
        data.put("concludedCount", concludedIds.size());
        data.put("takenDown", takenDown);
        return ResponseResult.okResult(data);
    }

    /**
     * 执行下架：置文章为 {@code TAKEN_DOWN} + 清索引 + 清向量。
     *
     * @return 是否真的改变了文章状态（false = 文章不存在或本就不在线上/待上线的状态）
     */
    private boolean takeDown(Long articleId, String reason, Date now) {
        if (articleId == null) {
            return false;
        }
        // 只对「已发布(9)」与「审核中(1)」下架：前者是线上内容，后者是即将上线的内容。
        // 草稿(0)/未通过(2) 本来就不对外可见，把它们标成"已下架"只会让作者困惑。
        // ⚠️ 顺带堵住一个洞：把审核中的文章置为 TAKEN_DOWN 后，
        // 审核通过时的条件更新（Submit→Published）就不再匹配，避免了"下架后又被放上线"。
        int affected = articleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
            .set(ApArticle::getStatus, ApArticle.Status.TAKEN_DOWN.getCode())
            .set(ApArticle::getReason, reason)
            .set(ApArticle::getUpdateTime, now)
            .eq(ApArticle::getId, articleId)
            .in(ApArticle::getStatus,
                List.of(ApArticle.Status.SUBMIT.getCode(), ApArticle.Status.PUBLISHED.getCode())));
        if (affected == 0) {
            log.warn("[AdminReport] 下架未改变文章状态（文章不存在或已非在线态）, articleId={}", articleId);
        }

        // 索引与向量的清理**不依赖 affected**：即使文章状态已不是在线态，
        // 也可能残留着上一次下架没清干净的文档/向量。这两步都幂等，条件为真时做、为假时做也无害。
        articleIndexRemover.removeFromIndex(articleId);
        embeddingService.deleteEmbedding(articleId);
        embeddingService.deleteChunks(articleId);
        return affected > 0;
    }

    /** 结案字段的统一写入片段：主记录与兄弟记录用完全相同的值，避免两条举报的结论对不上 */
    private LambdaUpdateWrapper<ApArticleReport> concludedUpdate(Integer handleResult, String reason,
                                                                 Integer operatorId, Date now) {
        return new LambdaUpdateWrapper<ApArticleReport>()
            .set(ApArticleReport::getStatus, ApArticleReport.STATUS_HANDLED)
            .set(ApArticleReport::getHandleResult, handleResult)
            .set(ApArticleReport::getHandleReason, reason)
            .set(ApArticleReport::getHandlerId, operatorId)
            .set(ApArticleReport::getHandleTime, now)
            .set(ApArticleReport::getNotifyStatus, ApArticleReport.NOTIFY_PENDING);
    }

    /** 操作人ID：优先取当前登录用户（与审计同一来源），兜底用运营身份里的账号ID */
    private Integer currentOperatorId() {
        ApUser current = AppThreadLocalUtil.getUser();
        if (current != null && current.getId() != null) {
            return current.getId();
        }
        AdminIdentity identity = AdminContext.get();
        return identity == null ? null : identity.getUserId();
    }

    /**
     * 当前操作人是否有下架权限。
     *
     * <p>没有运营身份时**返回 false**（fail-closed）：无法确认身份就不该执行不可逆的撤下动作。
     */
    private boolean canTakeDown() {
        AdminIdentity identity = AdminContext.get();
        return identity != null && identity.has(AdminPermission.CONTENT_TAKE_DOWN);
    }

    private String actionOf(Integer handleResult) {
        if (handleResult == null) {
            return ACTION_REJECT;
        }
        return switch (handleResult) {
            case ApArticleReport.RESULT_WARN_AUTHOR -> ACTION_WARN_AUTHOR;
            case ApArticleReport.RESULT_TAKE_DOWN -> ACTION_TAKE_DOWN;
            default -> ACTION_REJECT;
        };
    }

    /** 批量回填文章标题与状态（避免队列里每条一次查询） */
    private Map<Long, ApArticle> loadArticles(List<ApArticleReport> records) {
        List<Long> ids = records.stream().map(ApArticleReport::getArticleId)
            .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return articleMapper.selectList(new LambdaQueryWrapper<ApArticle>()
                .select(ApArticle::getId, ApArticle::getTitle, ApArticle::getStatus)
                .in(ApArticle::getId, ids))
            .stream().collect(Collectors.toMap(ApArticle::getId, a -> a, (a, b) -> a));
    }

    /**
     * 统计本页涉及文章的待处理举报数。
     *
     * <p>刻意在 Java 里分组而不是写 {@code GROUP BY} 聚合 SQL：本页最多 20 篇文章，
     * 待处理举报本就是个位数，一次带 where 的普通查询足够；换成聚合查询要多维护一段
     * 原生 SQL 和列别名映射（别名大小写还受驱动设置影响），收益不值这个复杂度。
     */
    private Map<Long, Integer> loadPendingCounts(List<ApArticleReport> records) {
        List<Long> ids = records.stream().map(ApArticleReport::getArticleId)
            .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (ApArticleReport r : reportMapper.selectList(new LambdaQueryWrapper<ApArticleReport>()
            .select(ApArticleReport::getArticleId)
            .in(ApArticleReport::getArticleId, ids)
            .eq(ApArticleReport::getStatus, ApArticleReport.STATUS_PENDING))) {
            counts.merge(r.getArticleId(), 1, Integer::sum);
        }
        return counts;
    }

    /** 举报截图字段是逗号分隔串，出参还原成列表；空串不产生一个空元素 */
    private List<String> splitImages(String imageUrls) {
        if (imageUrls == null || imageUrls.isBlank()) {
            return List.of();
        }
        return Arrays.stream(imageUrls.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
