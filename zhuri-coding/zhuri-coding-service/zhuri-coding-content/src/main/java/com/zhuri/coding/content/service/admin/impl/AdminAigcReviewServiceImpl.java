package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.aigc.AigcRecordMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.course.ApCourseChapterMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.admin.AdminAigcReviewService;
import com.zhuri.coding.content.service.aigc.AigcDetectService;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminAigcReviewVO;
import com.zhuri.coding.model.aigc.pojos.AigcRecord;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.course.pojos.ApCourseChapter;
import com.zhuri.coding.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AIGC 复核队列实现。
 *
 * <p>接口层面的取舍见 {@link AdminAigcReviewService}。这里只记载实现上的决定。
 *
 * <p><b>复核动作 = 先读后条件更新，再动业务表</b>：顺序不能换 ——
 * 先用 {@code WHERE id=? AND status=1} 抢到这条记录（两个运营同时点放行，
 * 只有一个人成功），抢到了才去清业务标记。放行失败（内容已被删除）抛异常，
 * 事务把状态更新一并回滚：不会出现"记录显示已放行、标记却还在"的半截状态。
 *
 * <p><b>标题/摘要批量装配</b>：一页最多 50 条记录，逐条查内容就是 50 次往返。
 * 按类型分三组各查一次，正文再单独一次（文章正文在 {@code ap_article_content}）。
 * 空集合直接跳过 —— {@code selectBatchIds} 收到空集合会拼出非法 SQL。
 *
 * <p><b>审计</b>：与 {@code AdminActivityServiceImpl} 同构 —— 写操作包在 try/catch 里，
 * 业务异常记失败审计后原样上抛；"记录不存在/状态不对"这类校验失败直接 return，
 * 不记失败审计（手误不值得淹没"谁试过但没成功"这条线索）。
 */
@Slf4j
@Service
public class AdminAigcReviewServiceImpl implements AdminAigcReviewService {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    /** 审计 detail 摘要上限 */
    private static final int DETAIL_MAX_LEN = 1000;

    @Autowired
    private AigcRecordMapper aigcRecordMapper;
    @Autowired
    private ApArticleMapper apArticleMapper;
    @Autowired
    private ApArticleContentMapper articleContentMapper;
    @Autowired
    private ApPinsMapper apPinsMapper;
    @Autowired
    private ApCourseChapterMapper courseChapterMapper;
    @Autowired
    private AigcDetectService aigcDetectService;
    @Autowired
    private AdminAuditRecorder auditRecorder;

    // ==================== 队列 ====================

    @Override
    public ResponseResult page(Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        LambdaQueryWrapper<AigcRecord> wrapper = new LambdaQueryWrapper<AigcRecord>()
            .eq(AigcRecord::getStatus, AigcRecord.STATUS_FLAGGED)
            // id 兜底排序：同分的记录顺序稳定，翻页才不会重复/漏行
            .orderByDesc(AigcRecord::getScore)
            .orderByDesc(AigcRecord::getId);
        IPage<AigcRecord> result = aigcRecordMapper.selectPage(new Page<>(p, s), wrapper);

        Map<Integer, Map<Long, ContentBrief>> briefs = loadBriefs(result.getRecords());
        List<AdminAigcReviewVO> list = result.getRecords().stream()
            .map(r -> toVO(r, briefs))
            .collect(Collectors.toList());
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    // ==================== 放行 / 确认 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult clear(Long id, String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_CONTENT,
            ACTION_CLEAR, TARGET_AIGC_RECORD, id == null ? null : String.valueOf(id), reason.trim());
        try {
            return doReview(id, AigcRecord.STATUS_CLEARED, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult confirm(Long id, String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_CONTENT,
            ACTION_CONFIRM, TARGET_AIGC_RECORD, id == null ? null : String.valueOf(id), reason.trim());
        try {
            return doReview(id, AigcRecord.STATUS_CONFIRMED, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    /** 放行与确认共用的主体：只有目标状态和"动不动业务表"不同 */
    private ResponseResult doReview(Long id, int targetStatus, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "记录ID不合法");
        }
        AigcRecord record = aigcRecordMapper.selectById(id);
        if (record == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "复核记录不存在");
        }
        if (record.getStatus() == null || record.getStatus() != AigcRecord.STATUS_FLAGGED) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "该记录当前状态为「" + AdminAigcReviewVO.describeStatus(record.getStatus())
                    + "」，无需复核（只有已标记的记录可以复核）");
        }

        int rows = aigcRecordMapper.update(null, new LambdaUpdateWrapper<AigcRecord>()
            .set(AigcRecord::getStatus, targetStatus)
            .set(AigcRecord::getUpdateTime, new Date())
            .eq(AigcRecord::getId, id)
            .eq(AigcRecord::getStatus, AigcRecord.STATUS_FLAGGED));
        if (rows == 0) {
            // 读出来之后被并发复核了（或页面停在旧状态重复提交）
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "记录状态已被其他操作改变，请刷新后重试");
        }

        if (targetStatus == AigcRecord.STATUS_CLEARED) {
            // 放行才动业务表；这里抛异常会让上面的状态更新一起回滚（同一事务）
            aigcDetectService.clearAigcFlag(record.getContentType(), record.getContentId());
        }

        audit.setDetail(truncate(describe(record, targetStatus)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminAigcReview] 复核完成, action={}, recordId={}, type={}, contentId={}, score={}",
            audit.getAction(), id, record.getContentType(), record.getContentId(), record.getScore());
        return ResponseResult.okResult();
    }

    // ==================== 出参装配 ====================

    private AdminAigcReviewVO toVO(AigcRecord r, Map<Integer, Map<Long, ContentBrief>> briefs) {
        AdminAigcReviewVO vo = new AdminAigcReviewVO();
        vo.setId(r.getId());
        vo.setContentType(r.getContentType());
        vo.setContentTypeDesc(AdminAigcReviewVO.describeContentType(r.getContentType()));
        vo.setContentId(r.getContentId());
        vo.setAuthorId(r.getAuthorId() == null ? null : r.getAuthorId().longValue());
        vo.setScore(r.getScore());
        vo.setSignalsJson(r.getSignalsJson());
        vo.setStatus(r.getStatus());
        vo.setStatusDesc(AdminAigcReviewVO.describeStatus(r.getStatus()));
        ContentBrief brief = r.getContentType() == null ? null
            : briefs.getOrDefault(r.getContentType(), Collections.emptyMap()).get(r.getContentId());
        if (brief != null) {
            vo.setContentTitle(brief.title);
            vo.setContentExcerpt(excerpt(brief.excerptSource));
        }
        vo.setCreateTime(r.getCreateTime());
        vo.setUpdateTime(r.getUpdateTime());
        return vo;
    }

    /** 正文摘要：压缩所有空白（含换行/markdown 源码的断行）为单个空格后截断 */
    private static String excerpt(String content) {
        if (content == null) {
            return null;
        }
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= AdminAigcReviewVO.EXCERPT_MAX_LEN) {
            return compact;
        }
        return compact.substring(0, AdminAigcReviewVO.EXCERPT_MAX_LEN) + "…";
    }

    // ==================== 内容标题/摘要批量装配 ====================

    /**
     * 按类型分三组批量取标题与正文来源。
     *
     * <p>返回 {@code 类型 -> 内容ID -> 摘要载体}；查不到的（内容已被删除）保持空载体，
     * 出参里 title/excerpt 为 null —— 不隐藏、不报错：复核对象是被静默降权的内容，
     * "内容不见了"本身就是运营需要看到的事实。
     */
    private Map<Integer, Map<Long, ContentBrief>> loadBriefs(List<AigcRecord> records) {
        Map<Long, ContentBrief> articles = new LinkedHashMap<>();
        Map<Long, ContentBrief> pins = new LinkedHashMap<>();
        Map<Long, ContentBrief> chapters = new LinkedHashMap<>();
        for (AigcRecord r : records) {
            if (r == null || r.getContentId() == null || r.getContentType() == null) {
                continue;
            }
            switch (r.getContentType()) {
                case AigcRecord.TYPE_ARTICLE -> articles.put(r.getContentId(), new ContentBrief());
                case AigcRecord.TYPE_PINS -> pins.put(r.getContentId(), new ContentBrief());
                case AigcRecord.TYPE_CHAPTER -> chapters.put(r.getContentId(), new ContentBrief());
                default -> {
                    // 未知类型不查库（没有对应的表），desc 会原样透出编码
                }
            }
        }
        loadArticleBriefs(articles);
        loadPinsBriefs(pins);
        loadChapterBriefs(chapters);

        Map<Integer, Map<Long, ContentBrief>> byType = new HashMap<>();
        byType.put(AigcRecord.TYPE_ARTICLE, articles);
        byType.put(AigcRecord.TYPE_PINS, pins);
        byType.put(AigcRecord.TYPE_CHAPTER, chapters);
        return byType;
    }

    /** 文章：标题在主表，正文在 content 表（各查一次，不复用 selectById） */
    private void loadArticleBriefs(Map<Long, ContentBrief> briefs) {
        if (briefs.isEmpty()) {
            return;
        }
        for (ApArticle a : apArticleMapper.selectBatchIds(briefs.keySet())) {
            ContentBrief b = briefs.get(a.getId());
            if (b != null) {
                b.title = a.getTitle();
            }
        }
        for (ApArticleContent c : articleContentMapper.selectList(new LambdaQueryWrapper<ApArticleContent>()
                .in(ApArticleContent::getArticleId, briefs.keySet()))) {
            ContentBrief b = briefs.get(c.getArticleId());
            // 一文多段历史遗留时取第一条；只做摘要，不需要拼全文
            if (b != null && b.excerptSource == null) {
                b.excerptSource = c.getContent();
            }
        }
    }

    /** 沸点：无标题，正文即 content */
    private void loadPinsBriefs(Map<Long, ContentBrief> briefs) {
        if (briefs.isEmpty()) {
            return;
        }
        for (ApPins p : apPinsMapper.selectBatchIds(briefs.keySet())) {
            ContentBrief b = briefs.get(p.getId());
            if (b != null) {
                b.excerptSource = p.getContent();
            }
        }
    }

    /** 课程小节：标题与正文都在主表 */
    private void loadChapterBriefs(Map<Long, ContentBrief> briefs) {
        if (briefs.isEmpty()) {
            return;
        }
        for (ApCourseChapter ch : courseChapterMapper.selectBatchIds(briefs.keySet())) {
            ContentBrief b = briefs.get(ch.getId());
            if (b != null) {
                b.title = ch.getTitle();
                b.excerptSource = ch.getContent();
            }
        }
    }

    /** 单条内容摘要的载体（标题可能没有，如沸点；正文可能为空，如纯图沸点） */
    private static final class ContentBrief {

        private String title;
        private String excerptSource;
    }

    // ==================== 审计摘要与小工具 ====================

    private static String describe(AigcRecord record, int targetStatus) {
        String typeDesc = AdminAigcReviewVO.describeContentType(record.getContentType());
        String content = typeDesc + "#" + record.getContentId();
        if (targetStatus == AigcRecord.STATUS_CLEARED) {
            return content + " 复核放行：清除 AI 标记（原疑似分 " + record.getScore() + "）";
        }
        return content + " 人工确认 AI 水文：保留标记（原疑似分 " + record.getScore() + "）";
    }

    /** 理由校验：必填、上限 500。由控制器与服务各调一次 —— 服务是最后一道闸口。 */
    private static ResponseResult validateReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (value.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }

    private static int normalizePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    /** 越界时静默回落到默认值：分页参数写错不值当让整个请求失败，但绝不按调用方给的任意值拉数据 */
    private static int normalizeSize(Integer size) {
        return (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;
    }

    private static Map<String, Object> pageData(List<?> list, long total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= DETAIL_MAX_LEN) {
            return text;
        }
        return text.substring(0, DETAIL_MAX_LEN);
    }
}
