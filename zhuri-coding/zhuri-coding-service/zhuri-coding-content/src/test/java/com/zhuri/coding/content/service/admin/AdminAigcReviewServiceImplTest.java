package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.aigc.AigcRecordMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.course.ApCourseChapterMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.content.service.admin.impl.AdminAigcReviewServiceImpl;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AIGC 复核队列单测。
 *
 * <p>盯住四件事，每条对应一种"接口返回成功、实际却不是那个样"的失效：
 * <ol>
 *   <li><b>队列只出已 flagged 的记录</b>，按疑似分降序（id 兜底排序防翻页抖动）；</li>
 *   <li><b>复核动作靠状态比对挡并发</b>：条件更新命中 0 行要明确报"请刷新后重试"，
 *       而不是当作成功；</li>
 *   <li><b>放行才动业务表、确认不动</b>：确认把业务标记也清了，等于绕过机器判定改写事实；</li>
 *   <li><b>放行失败必须整体失败</b>：内容已被删除时抛异常并记失败审计，
 *       绝不能留下"记录显示已放行、标记却还在"的半截状态。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AIGC 复核队列（AdminAigcReviewServiceImpl）")
class AdminAigcReviewServiceImplTest {

    private static final long RECORD_ID = 31L;
    private static final long ARTICLE_ID = 1001L;
    private static final String REASON = "人工通读全文，含大量真实代码与踩坑细节，非 AI 生成";

    @Mock
    private AigcRecordMapper aigcRecordMapper;

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private ApArticleContentMapper articleContentMapper;

    @Mock
    private ApPinsMapper apPinsMapper;

    @Mock
    private ApCourseChapterMapper courseChapterMapper;

    @Mock
    private AigcDetectService aigcDetectService;

    @Mock
    private AdminAuditRecorder auditRecorder;

    @InjectMocks
    private AdminAigcReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器靠实体元信息解析列名。单测没有 MyBatis 会话，必须先手动初始化
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AigcRecord.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticle.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApArticleContent.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPins.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCourseChapter.class);
    }

    // ==================== 造数 ====================

    private static AigcRecord record(long id, int type, long contentId, int score) {
        AigcRecord r = new AigcRecord();
        r.setId(id);
        r.setContentType(type);
        r.setContentId(contentId);
        r.setAuthorId(7);
        r.setScore(score);
        r.setSignalsJson("{\"burst\":80,\"repeat\":66}");
        r.setStatus(AigcRecord.STATUS_FLAGGED);
        r.setCreateTime(new Date());
        r.setUpdateTime(new Date());
        return r;
    }

    private static AigcRecord flaggedArticleRecord() {
        return record(RECORD_ID, AigcRecord.TYPE_ARTICLE, ARTICLE_ID, 87);
    }

    /** 捕获 selectPage 收到的分页对象与查询条件（泛型复杂，用引用容器避开 Captor 的原始类型转换） */
    private void stubSelectPage(Page<AigcRecord> resultPage,
                                AtomicReference<Page<AigcRecord>> pageArg,
                                AtomicReference<Wrapper<AigcRecord>> wrapperArg) {
        when(aigcRecordMapper.selectPage(any(), any())).thenAnswer(inv -> {
            pageArg.set(inv.getArgument(0));
            wrapperArg.set(inv.getArgument(1));
            return resultPage;
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseResult result) {
        return (Map<String, Object>) result.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<AdminAigcReviewVO> listOf(Map<String, Object> data) {
        return (List<AdminAigcReviewVO>) data.get("list");
    }

    private ApAdminAuditLog captureSuccessAudit() {
        ArgumentCaptor<ApAdminAuditLog> captor = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(captor.capture());
        return captor.getValue();
    }

    // ==================== 队列 ====================

    @Nested
    @DisplayName("队列")
    class Queue {

        @Test
        @DisplayName("只出已 flagged 的记录，按疑似分降序、id 兜底排序；分页参数原样下发")
        @SuppressWarnings("unchecked")
        void onlyFlaggedOrderedByScore() {
            AtomicReference<Page<AigcRecord>> pageArg = new AtomicReference<>();
            AtomicReference<Wrapper<AigcRecord>> wrapperArg = new AtomicReference<>();
            Page<AigcRecord> resultPage = new Page<>(2, 10);
            resultPage.setRecords(List.of());
            stubSelectPage(resultPage, pageArg, wrapperArg);

            ResponseResult result = service.page(2, 10);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            assertEquals(2, pageArg.get().getCurrent());
            assertEquals(10, pageArg.get().getSize());
            assertEquals(0L, dataOf(result).get("total"));

            Wrapper<AigcRecord> wrapper = wrapperArg.get();
            String sql = wrapper.getSqlSegment();
            Map<String, Object> params = ((LambdaQueryWrapper<AigcRecord>) wrapper).getParamNameValuePairs();
            assertTrue(sql.contains("status"), "必须按状态过滤：" + sql);
            assertTrue(params.containsValue(AigcRecord.STATUS_FLAGGED),
                "过滤值必须是已 flagged（1）：" + params);
            assertTrue(sql.contains("ORDER BY"), sql);
            assertTrue(sql.contains("score DESC"), "疑似分降序是队列的意义：" + sql);
            assertTrue(sql.contains("id DESC"), "同分记录要有稳定兜底排序，翻页才不抖动：" + sql);
        }

        @Test
        @DisplayName("非法分页参数兜底：page<=0/null → 1，size 越界/null → 20")
        void normalizesInvalidPaging() {
            AtomicReference<Page<AigcRecord>> pageArg = new AtomicReference<>();
            AtomicReference<Wrapper<AigcRecord>> wrapperArg = new AtomicReference<>();
            Page<AigcRecord> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of());
            stubSelectPage(resultPage, pageArg, wrapperArg);

            service.page(0, 999);
            assertEquals(1, pageArg.get().getCurrent());
            assertEquals(20, pageArg.get().getSize(), "size 超过单页上限按默认值，不按调用方的 999 拉数据");

            service.page(null, null);
            assertEquals(1, pageArg.get().getCurrent());
            assertEquals(20, pageArg.get().getSize());
        }

        @Test
        @DisplayName("三种内容类型各自装配标题/摘要，未知类型不查库、编码原样透出")
        void assemblesBriefsPerContentType() {
            AigcRecord article = record(RECORD_ID, AigcRecord.TYPE_ARTICLE, ARTICLE_ID, 87);
            AigcRecord pins = record(32L, AigcRecord.TYPE_PINS, 2001L, 76);
            AigcRecord chapter = record(33L, AigcRecord.TYPE_CHAPTER, 3001L, 72);
            AigcRecord unknown = record(34L, 9, 4001L, 60);
            AtomicReference<Page<AigcRecord>> pageArg = new AtomicReference<>();
            AtomicReference<Wrapper<AigcRecord>> wrapperArg = new AtomicReference<>();
            Page<AigcRecord> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of(article, pins, chapter, unknown));
            resultPage.setTotal(4);
            stubSelectPage(resultPage, pageArg, wrapperArg);

            ApArticle articleRow = new ApArticle();
            articleRow.setId(ARTICLE_ID);
            articleRow.setTitle("解读 JVM 内存模型");
            when(apArticleMapper.selectBatchIds(any())).thenReturn(List.of(articleRow));

            ApArticleContent contentRow = new ApArticleContent();
            contentRow.setArticleId(ARTICLE_ID);
            contentRow.setContent("JVM".repeat(100));
            when(articleContentMapper.selectList(any())).thenReturn(List.of(contentRow));

            ApPins pinsRow = new ApPins();
            pinsRow.setId(2001L);
            pinsRow.setContent("今天调试 G1 停顿，结论很短");
            when(apPinsMapper.selectBatchIds(any())).thenReturn(List.of(pinsRow));

            ApCourseChapter chapterRow = new ApCourseChapter();
            chapterRow.setId(3001L);
            chapterRow.setTitle("第一章 环境搭建");
            chapterRow.setContent("安装 JDK 与 Maven\n配置镜像源");
            when(courseChapterMapper.selectBatchIds(any())).thenReturn(List.of(chapterRow));

            ResponseResult result = service.page(1, 20);

            List<AdminAigcReviewVO> list = listOf(dataOf(result));
            assertEquals(4, list.size());
            assertEquals(4L, dataOf(result).get("total"));

            AdminAigcReviewVO voArticle = list.get(0);
            assertEquals("文章", voArticle.getContentTypeDesc());
            assertEquals(ARTICLE_ID, voArticle.getContentId());
            assertEquals(87, voArticle.getScore());
            assertEquals(7L, voArticle.getAuthorId());
            assertEquals("已标记", voArticle.getStatusDesc());
            assertEquals("解读 JVM 内存模型", voArticle.getContentTitle());
            assertNotNull(voArticle.getContentExcerpt());
            assertEquals(AdminAigcReviewVO.EXCERPT_MAX_LEN + 1, voArticle.getContentExcerpt().length(),
                "超长摘要截断后带省略号");
            assertTrue(voArticle.getContentExcerpt().endsWith("…"));

            AdminAigcReviewVO voPins = list.get(1);
            assertEquals("沸点", voPins.getContentTypeDesc());
            assertNull(voPins.getContentTitle(), "沸点没有标题列");
            assertEquals("今天调试 G1 停顿，结论很短", voPins.getContentExcerpt(),
                "短内容原样透出，不追加省略号");

            AdminAigcReviewVO voChapter = list.get(2);
            assertEquals("课程小节", voChapter.getContentTypeDesc());
            assertEquals("第一章 环境搭建", voChapter.getContentTitle());
            assertEquals("安装 JDK 与 Maven 配置镜像源", voChapter.getContentExcerpt(),
                "换行压缩为空格，摘要只支撑直觉判断");

            AdminAigcReviewVO voUnknown = list.get(3);
            assertEquals("9", voUnknown.getContentTypeDesc(), "未知类型编码原样透出，不伪装成'未知'");
            assertNull(voUnknown.getContentTitle(), "未知类型没有对应的表可查");

            // 每类各查一次，不逐行打库
            verify(apArticleMapper).selectBatchIds(any());
            verify(articleContentMapper).selectList(any());
            verify(apPinsMapper).selectBatchIds(any());
            verify(courseChapterMapper).selectBatchIds(any());
        }

        @Test
        @DisplayName("空队列不触发任何内容查询（selectBatchIds 收到空集合会拼出非法 SQL）")
        void emptyQueueSkipsContentQueries() {
            AtomicReference<Page<AigcRecord>> pageArg = new AtomicReference<>();
            AtomicReference<Wrapper<AigcRecord>> wrapperArg = new AtomicReference<>();
            Page<AigcRecord> resultPage = new Page<>(1, 20);
            resultPage.setRecords(List.of());
            stubSelectPage(resultPage, pageArg, wrapperArg);

            ResponseResult result = service.page(1, 20);

            assertTrue(listOf(dataOf(result)).isEmpty());
            verifyNoInteractions(apArticleMapper, articleContentMapper, apPinsMapper, courseChapterMapper);
        }
    }

    // ==================== 复核放行 ====================

    @Nested
    @DisplayName("复核放行")
    class Clear {

        @Test
        @DisplayName("放行：状态条件更新（WHERE status=1）→ 清业务标记 → 成功审计带动作/对象/理由")
        @SuppressWarnings("unchecked")
        void clearsBusinessFlagWithStatusGuard() {
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(flaggedArticleRecord());
            when(aigcRecordMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.clear(RECORD_ID, "  " + REASON + "  ");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());

            ArgumentCaptor<Wrapper<AigcRecord>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(aigcRecordMapper).update(isNull(), captor.capture());
            LambdaUpdateWrapper<AigcRecord> wrapper = (LambdaUpdateWrapper<AigcRecord>) captor.getValue();
            // ⚠️ eq 的参数注册是惰性的（ISqlSegment lambda）：必须先渲染 getSqlSegment()，
            // paramNameValuePairs 里才会出现 WHERE 的参数 —— 顺序反了会得到"参数缺失"的假失败
            String where = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(wrapper.getSqlSet().contains("status"), "SET 里必须有状态：" + wrapper.getSqlSet());
            assertTrue(params.containsValue(AigcRecord.STATUS_CLEARED),
                "目标状态是复核放行：" + params);
            assertTrue(params.containsValue(AigcRecord.STATUS_FLAGGED),
                "WHERE 必须带状态比对，挡住并发复核：" + where + " / " + params);
            assertTrue(where.contains("id ="), "WHERE 必须锚定到这条记录：" + where);

            verify(aigcDetectService).clearAigcFlag(AigcRecord.TYPE_ARTICLE, ARTICLE_ID);

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(ApAdminAuditLog.MODULE_CONTENT, audit.getModule());
            assertEquals(AdminAigcReviewService.ACTION_CLEAR, audit.getAction());
            assertEquals(AdminAigcReviewService.TARGET_AIGC_RECORD, audit.getTargetType());
            assertEquals(String.valueOf(RECORD_ID), audit.getTargetId());
            assertEquals(REASON, audit.getReason(), "理由要 trim 后落审计");
            assertTrue(audit.getDetail().contains("复核放行"), audit.getDetail());
            assertTrue(audit.getDetail().contains("87"), "原疑似分要进摘要：" + audit.getDetail());
        }

        @Test
        @DisplayName("记录不存在 → 数据不存在错误，不更新、不放行、不留审计")
        void missingRecordRejected() {
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(null);

            ResponseResult result = service.clear(RECORD_ID, REASON);

            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), result.getCode());
            verify(aigcRecordMapper, never()).update(any(), any());
            verifyNoInteractions(aigcDetectService, auditRecorder);
        }

        @Test
        @DisplayName("状态不是已标记（如已放行）→ 明确报'无需复核'，不静默成功")
        void notFlaggedRejected() {
            AigcRecord cleared = flaggedArticleRecord();
            cleared.setStatus(AigcRecord.STATUS_CLEARED);
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(cleared);

            ResponseResult result = service.clear(RECORD_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
            assertTrue(result.getMessage().contains("无需复核"), result.getMessage());
            verify(aigcRecordMapper, never()).update(any(), any());
            verifyNoInteractions(aigcDetectService, auditRecorder);
        }

        @Test
        @DisplayName("条件更新命中 0 行（并发复核）→ 报'请刷新后重试'，且绝不能再清业务标记")
        void concurrentChangeRejected() {
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(flaggedArticleRecord());
            when(aigcRecordMapper.update(isNull(), any())).thenReturn(0);

            ResponseResult result = service.clear(RECORD_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
            assertTrue(result.getMessage().contains("刷新后重试"), result.getMessage());
            verifyNoInteractions(aigcDetectService); // 输家不能动业务表
            verify(auditRecorder, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("业务表放行失败（内容已被删除）→ 异常上抛 + 失败审计，不留半截成功")
        void businessClearFailureFailsLoudly() {
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(flaggedArticleRecord());
            when(aigcRecordMapper.update(isNull(), any())).thenReturn(1);
            doThrow(new IllegalStateException("AIGC 放行：内容主表记录不存在（contentId=" + ARTICLE_ID + "，可能已被删除）"))
                .when(aigcDetectService).clearAigcFlag(anyInt(), any());

            assertThrows(IllegalStateException.class, () -> service.clear(RECORD_ID, REASON));

            verify(auditRecorder).recordFailure(any(ApAdminAuditLog.class), contains("内容主表"));
            verify(auditRecorder, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("理由缺失 / 超长 → 参数错误，不碰库、不留审计（手误不淹没'谁试过但没成功'）")
        void rejectsInvalidReason() {
            assertInvalid(service.clear(RECORD_ID, "   "), "操作理由");
            assertInvalid(service.clear(RECORD_ID, "水".repeat(501)), "500");
            assertInvalid(service.confirm(RECORD_ID, null), "操作理由");
            verifyNoInteractions(aigcRecordMapper, auditRecorder, aigcDetectService);
        }
    }

    // ==================== 人工确认 ====================

    @Nested
    @DisplayName("人工确认")
    class Confirm {

        @Test
        @DisplayName("确认：记录转'人工确认'，但绝不动业务标记 —— 确认不是处置，处置早已发生")
        void confirmsWithoutTouchingBusinessFlag() {
            AigcRecord pinsRecord = record(RECORD_ID, AigcRecord.TYPE_PINS, 2001L, 91);
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(pinsRecord);
            when(aigcRecordMapper.update(isNull(), any())).thenReturn(1);

            ResponseResult result = service.confirm(RECORD_ID, "典型的模板化车轱辘话，人工确认");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            verify(aigcDetectService, never()).clearAigcFlag(anyInt(), any());

            ApAdminAuditLog audit = captureSuccessAudit();
            assertEquals(AdminAigcReviewService.ACTION_CONFIRM, audit.getAction());
            assertEquals(AdminAigcReviewService.TARGET_AIGC_RECORD, audit.getTargetType());
            assertTrue(audit.getDetail().contains("人工确认"), audit.getDetail());
            assertTrue(audit.getDetail().contains("保留标记"), "确认的语义要写清：标记保留 " + audit.getDetail());
        }

        @Test
        @DisplayName("已放行的记录不能再'确认'：状态流转只有已标记 → 目标状态一条路")
        void cannotConfirmClearedRecord() {
            AigcRecord cleared = flaggedArticleRecord();
            cleared.setStatus(AigcRecord.STATUS_CLEARED);
            when(aigcRecordMapper.selectById(RECORD_ID)).thenReturn(cleared);

            ResponseResult result = service.confirm(RECORD_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode());
            verify(aigcRecordMapper, never()).update(any(), any());
            verifyNoInteractions(aigcDetectService, auditRecorder);
        }
    }

    // ==================== 小工具 ====================

    private static void assertInvalid(ResponseResult result, String expectedFragment) {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode(), result.getMessage());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains(expectedFragment),
            "错误信息要能直接照着改：" + result.getMessage());
    }
}
