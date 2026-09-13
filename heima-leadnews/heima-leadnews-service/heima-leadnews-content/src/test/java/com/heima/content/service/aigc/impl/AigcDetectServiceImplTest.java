package com.heima.content.service.aigc.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.content.mapper.aigc.AigcRecordMapper;
import com.heima.content.mapper.article.ApArticleContentMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.course.ApCourseChapterMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.aigc.pojos.AigcRecord;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.article.pojos.ApArticleContent;
import com.heima.model.article.pojos.ApArticleEmbedding;
import com.heima.model.course.pojos.ApCourseChapter;
import com.heima.model.pins.pojos.ApPins;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * AIGC 水文检测单测。
 *
 * <p>覆盖：L1 快检入口（文章/沸点/章节）的短路、记录落库、高分打标；
 * 异步复核（L2 作者画像 + combineScore）与 L3 LLM 纠偏清标（防误伤）。
 * aiSseExecutor 桩为同步执行，使 deepReview 在当前线程内可断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AIGC 水文检测（L1 快检 + L2 画像 + L3 纠偏）")
class AigcDetectServiceImplTest {

    /** AI 水文样张：句长整齐 + 模板词密集 + 4-gram 高度重复（期望 L1 高分 >=70） */
    private static final String AIGC_TEXT =
        "首先我们要分析问题的重要性，其次要考虑解决方案的整体可行性，然后需要评估具体实施的关键步骤，最后总结一下本次实践的收获。"
            .repeat(12);

    /** 真人样张：句长参差 + 数字/代码锚点密集（期望 L1 低分 <45） */
    private static final String REAL_TEXT =
        "今天排查了三个数据库连接池的配置问题。核心原因在于 maxPoolSize 设得太小只有 10，生产高峰期 120 个并发直接把连接耗尽。" +
        "我调整到 30 并用 jstack 验证线程状态，重启后 p99 从 2.3s 降到 0.8s。另外还修复了一个慢查询：原 SQL 少了联合索引 (user_id,status)，" +
        "补上后执行计划从全表扫描变成 index range，耗时从 180ms 降到 12ms。代码改动很小但收益明显。";

    private static final double[] VEC = {0.1, 0.2, 0.3, 0.4};
    private static final double[] VEC_OPPOSITE = {-0.1, -0.2, -0.3, -0.4};

    @Mock private AigcRecordMapper aigcRecordMapper;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApArticleContentMapper contentMapper;
    @Mock private ApPinsMapper apPinsMapper;
    @Mock private ApCourseChapterMapper courseChapterMapper;
    @Mock private ArticleEmbeddingServiceImpl embeddingService;
    @Mock private ChatModel chatModel;
    @Mock private Executor aiSseExecutor;

    @InjectMocks
    private AigcDetectServiceImpl service;

    @BeforeEach
    void syncReviewExecutor() {
        // 异步复核改为同步执行，便于断言 deepReview 结果（仅在触发复核的用例生效）
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(aiSseExecutor).execute(any(Runnable.class));
    }

    // ---------- 辅助 ----------

    private ApArticle article(Long id, String title, Long authorId) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        a.setAuthorId(authorId);
        a.setStatus(Status.PUBLISHED.getCode());
        return a;
    }

    private ApArticleContent content(String text) {
        ApArticleContent ac = new ApArticleContent();
        ac.setArticleId(1L);
        ac.setContent(text);
        return ac;
    }

    private ApArticleEmbedding emb(double[] vec) {
        ApArticleEmbedding e = new ApArticleEmbedding();
        e.setEmbedding(vec);
        return e;
    }

    // ==================== 文章入口：短路与记录 ====================

    @Test
    @DisplayName("articleId 为空直接忽略")
    void articleNullId() {
        service.detectAndFlagArticle(null);
        verify(apArticleMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("文章不存在时不落记录")
    void articleNotExist() {
        when(apArticleMapper.selectById(1L)).thenReturn(null);
        service.detectAndFlagArticle(1L);
        verify(aigcRecordMapper, never()).insert(any(AigcRecord.class));
    }

    @Test
    @DisplayName("正文为空白时不落记录")
    void articleBlankContent() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, "t", 7L));
        when(contentMapper.selectOne(any())).thenReturn(content("   "));
        service.detectAndFlagArticle(1L);
        verify(aigcRecordMapper, never()).insert(any(AigcRecord.class));
    }

    @Test
    @DisplayName("真人风格低分仅记录，不触发复核与打标")
    void articleLowScoreRecordOnly() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, "t", 7L));
        when(contentMapper.selectOne(any())).thenReturn(content(REAL_TEXT));
        when(aigcRecordMapper.selectOne(any())).thenReturn(null);

        service.detectAndFlagArticle(1L);

        ArgumentCaptor<AigcRecord> cap = ArgumentCaptor.forClass(AigcRecord.class);
        verify(aigcRecordMapper).insert(cap.capture());
        AigcRecord rec = cap.getValue();
        assertTrue(rec.getScore() < 45, "真人文本 L1 应低于复核阈值(45)，实际=" + rec.getScore());
        assertEquals(AigcRecord.STATUS_RECORD, rec.getStatus());
        verify(apArticleMapper, never()).updateById(any(ApArticle.class));
    }

    @Test
    @DisplayName("AI 水文高分：落库 flagged 并打标 is_aigc=1")
    void articleHighScoreFlagged() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, "t", null));
        when(contentMapper.selectOne(any())).thenReturn(content(AIGC_TEXT));
        when(aigcRecordMapper.selectOne(any())).thenReturn(null);

        service.detectAndFlagArticle(1L);

        ArgumentCaptor<AigcRecord> recCap = ArgumentCaptor.forClass(AigcRecord.class);
        verify(aigcRecordMapper).insert(recCap.capture());
        assertTrue(recCap.getValue().getScore() >= 70, "水文文本 L1 应达到打标阈值(70)，实际=" + recCap.getValue().getScore());
        assertEquals(AigcRecord.STATUS_FLAGGED, recCap.getValue().getStatus());

        ArgumentCaptor<ApArticle> artCap = ArgumentCaptor.forClass(ApArticle.class);
        // 入口 applyFlag + 异步复核 applyFlag 各一次；断言最后（复核）打标结果
        verify(apArticleMapper, atLeastOnce()).updateById(artCap.capture());
        assertEquals(Integer.valueOf(1), artCap.getValue().getIsAigc());
    }

    // ==================== 沸点 / 课程小节入口 ====================

    @Test
    @DisplayName("沸点内容为空不落记录，高分则打标")
    void pinsBlankAndFlagged() {
        service.detectAndFlagPins(null);
        verify(apPinsMapper, never()).updateById(any(ApPins.class));

        ApPins pins = new ApPins();
        pins.setId(2L);
        pins.setAuthorId(7L);
        pins.setContent(AIGC_TEXT);
        when(apPinsMapper.selectById(2L)).thenReturn(pins);
        when(aigcRecordMapper.selectOne(any())).thenReturn(null);

        service.detectAndFlagPins(2L);

        ArgumentCaptor<ApPins> pinsCap = ArgumentCaptor.forClass(ApPins.class);
        verify(apPinsMapper, atLeastOnce()).updateById(pinsCap.capture());
        assertEquals(Integer.valueOf(1), pinsCap.getValue().getIsAigc());
    }

    @Test
    @DisplayName("课程小节空白不落记录，高分则打标")
    void chapterBlankAndFlagged() {
        ApCourseChapter blank = new ApCourseChapter();
        blank.setId(3L);
        blank.setContent("  ");
        when(courseChapterMapper.selectById(3L)).thenReturn(blank);
        service.detectAndFlagChapter(3L);
        verify(aigcRecordMapper, never()).insert(any(AigcRecord.class));

        ApCourseChapter ch = new ApCourseChapter();
        ch.setId(4L);
        ch.setTitle("Chapter");
        ch.setContent(AIGC_TEXT);
        when(courseChapterMapper.selectById(4L)).thenReturn(ch);
        when(aigcRecordMapper.selectOne(any())).thenReturn(null);

        service.detectAndFlagChapter(4L);

        ArgumentCaptor<ApCourseChapter> chCap = ArgumentCaptor.forClass(ApCourseChapter.class);
        verify(courseChapterMapper).updateById(chCap.capture());
        assertEquals(Integer.valueOf(1), chCap.getValue().getIsAigc());
        assertNotNull(chCap.getValue().getAigcScore());
    }

    // ==================== 异步复核：L2 作者画像 + L3 LLM 纠偏 ====================

    @Test
    @DisplayName("异步复核：L2 画像是人难以匹配高分 → L3 判 normal → 纠偏清标")
    void deepReviewWithL2AndL3Correction() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, "t", 7L));
        when(contentMapper.selectOne(any())).thenReturn(content(AIGC_TEXT));
        when(aigcRecordMapper.selectOne(any())).thenReturn(null);
        // 作者历史 3 篇（>= AUTHOR_HISTORY_MIN）
        when(apArticleMapper.selectList(any())).thenReturn(List.of(
            article(11L, "h1", 7L), article(12L, "h2", 7L), article(13L, "h3", 7L)));
        // 新文向量与历史均值方向相反 → cos~0 → 作者偏离分 100（L2 高分，failed-open 不阻止复核）
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(embeddingService.getEmbedding(anyLong())).thenReturn(emb(VEC_OPPOSITE));
        // L3：模型判 normal(40) < 70 → 纠偏清标（防误伤）
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
            new Generation(new AssistantMessage(
                "{\"verdict\":\"normal\",\"score\":40,\"reason\":\"含具体实践案例\"}")))));

        service.detectAndFlagArticle(1L);

        // 同步执行器会让 deepReview 先于入口 applyFlag 执行，捕获顺序为 [纠偏(0,69) → 入口打标(1,97)]，
        // 因此不能断言"最后一条"，而应断言更新序列中"存在" L3 纠偏清标更新。
        ArgumentCaptor<ApArticle> artCap = ArgumentCaptor.forClass(ApArticle.class);
        verify(apArticleMapper, atLeastOnce()).updateById(artCap.capture());
        List<ApArticle> updates = artCap.getAllValues();
        boolean flaggedThenCorrected = updates.stream().anyMatch(a -> a.getAigcScore() != null
            && a.getAigcScore() >= 70 && Integer.valueOf(1).equals(a.getIsAigc()));
        boolean corrected = updates.stream().anyMatch(a -> Integer.valueOf(0).equals(a.getIsAigc())
            && a.getAigcScore() != null && a.getAigcScore() < 70);
        assertTrue(flaggedThenCorrected, "入口应先用高分(>=70)打标，实际更新=" + updates);
        assertTrue(corrected, "L3 判 normal 后应存在纠偏清标更新（is_aigc=0, score<70），实际更新=" + updates);
    }

    @Test
    @DisplayName("异步复核：模型不可用时维持 L2 决断（fail-open 不抛异常）")
    void deepReviewWhenLlmUnavailable() {
        when(apArticleMapper.selectById(1L)).thenReturn(article(1L, "t", 7L));
        when(contentMapper.selectOne(any())).thenReturn(content(AIGC_TEXT));
        when(aigcRecordMapper.selectOne(any())).thenReturn(null);
        when(apArticleMapper.selectList(any())).thenReturn(List.of(
            article(11L, "h1", 7L), article(12L, "h2", 7L), article(13L, "h3", 7L)));
        when(embeddingService.generateEmbedding(anyString())).thenReturn(VEC);
        when(embeddingService.getEmbedding(anyLong())).thenReturn(emb(VEC_OPPOSITE));
        // ChatModel 未 stub → ChatClient 调用抛 NPE → llmReview fail-open 返回 null（维持 L1/L2 决断，不崩溃）
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        service.detectAndFlagArticle(1L);

        ArgumentCaptor<ApArticle> artCap = ArgumentCaptor.forClass(ApArticle.class);
        verify(apArticleMapper, atLeastOnce()).updateById(artCap.capture());
        assertEquals(Integer.valueOf(1), artCap.getValue().getIsAigc(),
            "LLM 不可用时应维持 L2 高分决断（仍 flagged）");
    }
}