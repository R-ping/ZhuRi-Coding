package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.common.admin.AdminContext;
import com.zhuri.coding.common.admin.AdminIdentity;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleReportMapper;
import com.zhuri.coding.content.service.admin.impl.AdminReportServiceImpl;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApArticleReport;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 运营侧举报处置单测。
 *
 * <p>这套用例盯住的是「闭环有没有真的闭上」，而不是分支覆盖率：
 * <ul>
 *   <li>处置只能发生一次（并发下靠条件更新认领，第二个请求必须被挡住）；</li>
 *   <li>下架必须真的下线 —— 状态、检索索引、向量三处都要动，少一处就等于没下架；</li>
 *   <li>回执粒度是举报人（同文章的每条举报各一份），作者告知是文章级（只发一次）；</li>
 *   <li>越权的下架尝试不能改任何数据，但必须留痕。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营侧举报处置（AdminReportServiceImpl）")
class AdminReportServiceImplTest {

    private static final Long REPORT_ID = 5001L;
    private static final Long ARTICLE_ID = 9001L;
    private static final Integer REPORTER_ID = 3001;
    private static final Long AUTHOR_ID = 4001L;
    private static final Integer OPERATOR_ID = 7777;

    @Mock
    private ApArticleReportMapper reportMapper;
    @Mock
    private ApArticleMapper articleMapper;
    @Mock
    private ArticleIndexRemover articleIndexRemover;
    @Mock
    private AdminReceiptDispatcher receiptDispatcher;
    @Mock
    private AdminAuditRecorder auditRecorder;
    @Mock
    private ArticleEmbeddingServiceImpl embeddingService;

    @InjectMocks
    private AdminReportServiceImpl service;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus 3.5.x 的 Lambda 包装器要从实体元信息里解析列名，单测没有 MyBatis 会话，
        // 必须先手动初始化，否则 new LambdaQueryWrapper<>().select(X::getY) 会抛
        // "can not find lambda cache for this entity"（本仓既有测试同此做法）
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleReport.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);

        ApUser operator = new ApUser();
        operator.setId(OPERATOR_ID);
        AppThreadLocalUtil.setUser(operator);
        // OPERATOR：既有 REPORT_HANDLE 也有 CONTENT_TAKE_DOWN，"正常处置路径"用这个身份
        AdminContext.set(new AdminIdentity(OPERATOR_ID, List.of("OPERATOR")));
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
        AdminContext.clear();
    }

    // ==================== 队列查询 ====================

    @Test
    @DisplayName("队列：批量回填文章标题与状态、统计同文章待处理数、拆分截图串")
    void pageFillsArticleInfoAndPendingCount() {
        ApArticleReport first = pendingReport(REPORT_ID, REPORTER_ID);
        ApArticleReport second = pendingReport(REPORT_ID + 1, REPORTER_ID + 1);
        Page<ApArticleReport> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of(first, second));
        dbPage.setTotal(2);
        when(reportMapper.selectPage(any(), any())).thenReturn(dbPage);
        when(articleMapper.selectList(any())).thenReturn(List.of(publishedArticle()));
        when(reportMapper.selectList(any())).thenReturn(List.of(first, second));

        ResponseResult result = service.page(null, 1, 20);

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2L, data.get("total"));
        @SuppressWarnings("unchecked")
        List<com.zhuri.coding.model.admin.vos.ArticleReportVO> list =
            (List<com.zhuri.coding.model.admin.vos.ArticleReportVO>) data.get("list");
        assertEquals(2, list.size());
        assertEquals("被举报的文章", list.get(0).getArticleTitle());
        assertEquals((int) ApArticle.Status.PUBLISHED.getCode(),
            list.get(0).getArticleStatus().intValue());
        assertEquals(2, list.get(0).getPendingCountOfArticle().intValue(),
            "同文章两条待处理举报，两行都该看到 2");
        assertEquals(List.of("http://img/a.png", "http://img/b.png"), list.get(0).getImageUrls());
    }

    @Test
    @DisplayName("队列：页大小越界（0 / 过大）回落到默认 20，避免一次拉爆")
    void pageClampsSize() {
        Page<ApArticleReport> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of());
        dbPage.setTotal(0);
        when(reportMapper.selectPage(any(), any())).thenReturn(dbPage);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) service.page(0, 0, 999).getData();

        assertEquals(1, data.get("page"));
        assertEquals(20, data.get("size"));
    }

    // ==================== 处置：前置与幂等 ====================

    @Test
    @DisplayName("处置：举报记录不存在 → 直接报错，不做任何副作用")
    void handleMissingReport() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(null);

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_REJECT, "无违规");

        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode().intValue());
        verify(receiptDispatcher, never()).deliverReportReceipt(any());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("处置：已被别人处置过（条件更新认领失败）→ 报错并停手，不重复发回执")
    void handleAlreadyConcluded() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(0);

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_TAKE_DOWN, "违规");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("已处置"));
        verify(articleMapper, never()).update(any(), any());
        verify(articleIndexRemover, never()).removeFromIndex(any());
        verify(receiptDispatcher, never()).deliverReportReceipt(any());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    // ==================== 处置：驳回 ====================

    @Test
    @DisplayName("处置·驳回：只结案 + 回执举报人，不动内容、不打扰作者")
    void handleReject() {
        ApArticleReport report = pendingReport(REPORT_ID, REPORTER_ID);
        when(reportMapper.selectById(REPORT_ID)).thenReturn(report);
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectList(any())).thenReturn(List.of());

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_REJECT, "核实后未见违规");

        assertEquals(200, result.getCode().intValue());
        verify(articleMapper, never()).update(any(), any());
        verify(articleIndexRemover, never()).removeFromIndex(any());
        verify(embeddingService, never()).deleteEmbedding(any());
        verify(receiptDispatcher).deliverReportReceipt(REPORT_ID);
        verify(receiptDispatcher, never()).notifyAuthorOfHandling(any(), any(), any(), any());

        ApAdminAuditLog audit = captureSuccessAudit();
        assertEquals(ApAdminAuditLog.MODULE_REPORT, audit.getModule());
        assertEquals(AdminReportService.ACTION_REJECT, audit.getAction());
        assertEquals(String.valueOf(REPORT_ID), audit.getTargetId());
    }

    // ==================== 处置：警告作者 ====================

    @Test
    @DisplayName("处置·警告：告知作者但不改内容状态、不清索引")
    void handleWarnAuthor() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectList(any())).thenReturn(List.of());

        service.handle(REPORT_ID, ApArticleReport.RESULT_WARN_AUTHOR, "标题党");

        verify(articleMapper, never()).update(any(), any());
        verify(articleIndexRemover, never()).removeFromIndex(any());
        verify(receiptDispatcher).notifyAuthorOfHandling(
            eq(ARTICLE_ID), eq(AUTHOR_ID), eq(ApArticleReport.RESULT_WARN_AUTHOR), eq("标题党"));
        assertEquals(AdminReportService.ACTION_WARN_AUTHOR, captureSuccessAudit().getAction());
    }

    // ==================== 处置：下架 ====================

    @Test
    @DisplayName("处置·下架：文章转下架态 + 清检索索引 + 清向量 + 回执举报人 + 告知作者")
    void handleTakeDown() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(articleMapper.update(isNull(), any())).thenReturn(1);

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_TAKE_DOWN, "内容违规");

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertTrue((Boolean) data.get("takenDown"));

        // 三处都要动：状态、检索索引、向量。少一处内容就还在某个入口可见。
        ArgumentCaptor<Wrapper<ApArticle>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).update(isNull(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "要把文章置为下架态");
        assertTrue(sql.contains("IN"), "WHERE 必须带状态白名单——无条件更新会把草稿也标成'已下架'");
        verify(articleIndexRemover).removeFromIndex(ARTICLE_ID);
        verify(embeddingService).deleteEmbedding(ARTICLE_ID);
        verify(embeddingService).deleteChunks(ARTICLE_ID);
        verify(receiptDispatcher).deliverReportReceipt(REPORT_ID);
        verify(receiptDispatcher).notifyAuthorOfHandling(
            eq(ARTICLE_ID), eq(AUTHOR_ID), eq(ApArticleReport.RESULT_TAKE_DOWN), eq("内容违规"));
        assertEquals(AdminReportService.ACTION_TAKE_DOWN, captureSuccessAudit().getAction());
    }

    @Test
    @DisplayName("处置·下架：文章已不在线（条件更新 0 行）仍清理索引与向量 —— 上次没清干净的残留要能自愈")
    void handleTakeDownCleansIndexEvenWhenArticleNotOnline() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(articleMapper.update(isNull(), any())).thenReturn(0);

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_TAKE_DOWN, "内容违规");

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertFalse((Boolean) data.get("takenDown"), "状态没变要如实回报（便于前端提示「内容已不在线」）");
        verify(articleIndexRemover).removeFromIndex(ARTICLE_ID);
        verify(embeddingService).deleteEmbedding(ARTICLE_ID);
    }

    @Test
    @DisplayName("处置·下架：审核员（无 CONTENT_TAKE_DOWN）→ 拒绝、零写入，但留一条失败审计")
    void handleTakeDownDeniedForAuditor() {
        AdminContext.set(new AdminIdentity(OPERATOR_ID, List.of("AUDITOR")));
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_TAKE_DOWN, "内容违规");

        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), result.getCode().intValue());
        verify(reportMapper, never()).update(any(), any());
        verify(articleMapper, never()).update(any(), any());
        verify(articleIndexRemover, never()).removeFromIndex(any());
        verify(auditRecorder, never()).recordSuccess(any());
        verify(auditRecorder).recordFailure(any(ApAdminAuditLog.class), any());
    }

    @Test
    @DisplayName("处置·下架：没有运营上下文（身份解析失败）→ fail-closed 拒绝，不执行不可逆动作")
    void handleTakeDownDeniedWithoutIdentity() {
        AdminContext.clear();
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_TAKE_DOWN, "内容违规");

        assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), result.getCode().intValue());
        verify(articleIndexRemover, never()).removeFromIndex(any());
    }

    // ==================== 批量结案 ====================

    @Test
    @DisplayName("处置：同文章其余待处理举报一并结案，且每个举报人各收一份回执")
    void handleConcludesSiblingReports() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        ApArticleReport sibling1 = pendingReport(REPORT_ID + 1, REPORTER_ID + 1);
        ApArticleReport sibling2 = pendingReport(REPORT_ID + 2, REPORTER_ID + 2);
        when(reportMapper.selectList(any())).thenReturn(List.of(sibling1, sibling2));

        ResponseResult result = service.handle(REPORT_ID, ApArticleReport.RESULT_REJECT, "核实后未见违规");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(3, data.get("concludedCount"));
        // 结案粒度是文章，回执粒度是举报人：3 条举报 → 3 份回执，一份都不能少
        verify(receiptDispatcher).deliverReportReceipt(REPORT_ID);
        verify(receiptDispatcher).deliverReportReceipt(REPORT_ID + 1);
        verify(receiptDispatcher).deliverReportReceipt(REPORT_ID + 2);
        verify(receiptDispatcher, times(3)).deliverReportReceipt(any());
    }

    // ==================== 失败留痕 ====================

    @Test
    @DisplayName("处置：写入过程中抛异常 → 记一条失败审计后原样抛出（不能把异常吞成'处置成功'）")
    void handleRecordsFailureAndRethrows() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(articleMapper.update(isNull(), any())).thenReturn(1);
        RuntimeException boom = new RuntimeException("DB 连接断了");
        org.mockito.Mockito.doThrow(boom).when(articleIndexRemover).removeFromIndex(ARTICLE_ID);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> service.handle(REPORT_ID, ApArticleReport.RESULT_TAKE_DOWN, "内容违规"));

        assertEquals(boom, thrown);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).recordFailure(any(ApAdminAuditLog.class), msgCaptor.capture());
        assertEquals("DB 连接断了", msgCaptor.getValue());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("处置：审计条目一律带上目标类型与 ID，事后才能按对象回溯")
    void auditEntryCarriesTarget() {
        when(reportMapper.selectById(REPORT_ID)).thenReturn(pendingReport(REPORT_ID, REPORTER_ID));
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectList(any())).thenReturn(List.of());

        service.handle(REPORT_ID, ApArticleReport.RESULT_REJECT, "核实后未见违规");

        ApAdminAuditLog audit = captureSuccessAudit();
        assertEquals("REPORT", audit.getTargetType());
        assertEquals(String.valueOf(REPORT_ID), audit.getTargetId());
        assertNotNull(audit.getDetail(), "变更摘要要落下来：结了几条、有没有真的下架");
        assertTrue(audit.getDetail().contains("articleId=" + ARTICLE_ID));
    }

    // ==================== 工具 ====================

    private ApAdminAuditLog captureSuccessAudit() {
        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(captor.capture());
        return captor.getValue();
    }

    private ApArticleReport pendingReport(Long id, Integer reporterId) {
        ApArticleReport report = new ApArticleReport();
        report.setId(id);
        report.setArticleId(ARTICLE_ID);
        report.setAuthorId(AUTHOR_ID);
        report.setUserId(reporterId);
        report.setReason("涉及人身攻击");
        report.setDescription("在评论区辱骂他人");
        report.setImageUrls("http://img/a.png,http://img/b.png");
        report.setStatus(ApArticleReport.STATUS_PENDING);
        report.setCreatedTime(new Date());
        return report;
    }

    private ApArticle publishedArticle() {
        ApArticle article = new ApArticle();
        article.setId(ARTICLE_ID);
        article.setTitle("被举报的文章");
        article.setAuthorId(AUTHOR_ID);
        article.setStatus(ApArticle.Status.PUBLISHED.getCode());
        return article;
    }

}
