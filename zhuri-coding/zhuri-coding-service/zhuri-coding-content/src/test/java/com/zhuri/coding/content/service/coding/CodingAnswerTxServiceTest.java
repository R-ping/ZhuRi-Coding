package com.zhuri.coding.content.service.coding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodingAnswerTxService 单元测试（每日一题 · 简答落库事务）
 *
 * 覆盖：重复提交的两道防线（预查询 + 唯一键）、未评估与已评估两种落库口径、
 * 领域分布按标签累计（含单题最多 3 个标签）、方向随作答落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodingAnswerTxService 简答落库事务")
class CodingAnswerTxServiceTest {

    private static final Integer USER_ID = 1001;
    private static final Date TODAY = Date.valueOf(LocalDate.now());

    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;

    @InjectMocks
    private CodingAnswerTxService service;

    // ---------- 辅助 ----------

    private ApCodingDailyPool pool() {
        ApCodingDailyPool p = new ApCodingDailyPool();
        p.setId(5L);
        p.setDirection("Java 后端");
        p.setTags("Java,并发");
        return p;
    }

    private CodingDailyEvaluator.Result result(Integer level) {
        CodingDailyEvaluator.Result r = new CodingDailyEvaluator.Result();
        if (level == null) {
            r.pending = true;
            r.comment = CodingDailyEvaluator.PENDING_COMMENT;
        } else {
            r.pending = false;
            r.level = level;
            r.covered = List.of("可见性");
            r.comment = "讲到了可见性";
        }
        return r;
    }

    private void givenNoDailyRecord() {
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    // ==================== 重复提交 ====================

    @Test
    @DisplayName("saveAnswer - 当日已答：预查询拦截，抛异常且不落库")
    void testAlreadyAnswered() {
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new ApCodingAnswerRecord());

        assertThrows(IllegalStateException.class,
            () -> service.saveAnswer(USER_ID, pool(), "作答", 10, result(4)));
        verify(recordMapper, org.mockito.Mockito.never()).insert(any(ApCodingAnswerRecord.class));
    }

    @Test
    @DisplayName("saveAnswer - 并发越过预检查：唯一键拦下，转成同一语义异常")
    void testUniqueKeyConflict() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class)))
            .thenThrow(new DuplicateKeyException("dup"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.saveAnswer(USER_ID, pool(), "作答", 10, result(4)));
        assertTrue(ex.getMessage().contains("今日一题已作答"));
    }

    // ==================== 落库口径 ====================

    @Test
    @DisplayName("saveAnswer - 已评估：等级/点评/考点清单落库，统计累计等级")
    void testSaveEvaluated() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ApCodingAnswerRecord saved = service.saveAnswer(USER_ID, pool(), "作答", 12, result(4));

        assertEquals(5L, saved.getPoolId());
        assertEquals(4, saved.getLevel());
        assertEquals("[\"可见性\"]", saved.getCovered());
        assertEquals("[]", saved.getMissing());

        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        assertEquals("Java 后端", stat.getValue().getDirection());
        assertEquals(1, stat.getValue().getTotalCount());
        assertTrue(stat.getValue().getTagStats().contains("levelSum"));
    }

    @Test
    @DisplayName("saveAnswer - 未评估：等级为 null，统计只加 total 不加 levelSum")
    void testSavePending() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ApCodingAnswerRecord saved = service.saveAnswer(USER_ID, pool(), "作答", null, result(null));

        assertNull(saved.getLevel());
        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        assertEquals(1, stat.getValue().getTotalCount());
        assertTrue(stat.getValue().getTagStats().contains("total"));
        assertTrue(!stat.getValue().getTagStats().contains("levelSum"));
    }

    @Test
    @DisplayName("领域分布合并：单题最多取前 3 个标签")
    void testMergeTagLimitsToThree() {
        givenNoDailyRecord();
        ApCodingDailyPool manyTags = pool();
        manyTags.setTags("A,B,C,D,E");
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveAnswer(USER_ID, manyTags, "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        String tagStats = stat.getValue().getTagStats();
        assertTrue(tagStats.contains("\"A\"") && tagStats.contains("\"C\""));
        assertTrue(!tagStats.contains("\"D\"") && !tagStats.contains("\"E\""));
    }

    // ==================== 回放与统计 ====================

    @Test
    @DisplayName("findDailyRecord - 只查当天那条")
    void testFindDailyRecord() {
        ApCodingAnswerRecord record = new ApCodingAnswerRecord();
        record.setPoolId(5L);
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

        assertEquals(5L, service.findDailyRecord(USER_ID, TODAY).getPoolId());
    }

    @Test
    @DisplayName("listRecent - 按作答日期倒序取最近 N 条")
    void testListRecent() {
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(new ApCodingAnswerRecord()));

        assertEquals(1, service.listRecent(USER_ID, 5).size());
    }

    @Test
    @DisplayName("统计首插撞唯一键：回读后走更新分支，不抛异常")
    void testStatUpsertRace() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(null)                       // 第一次读：不存在
            .thenReturn(new ApCodingUserStat());    // 撞唯一键回读：存在
        when(statMapper.insert(any(ApCodingUserStat.class)))
            .thenThrow(new DuplicateKeyException("dup"));

        service.saveAnswer(USER_ID, pool(), "作答", null, result(4));

        verify(statMapper).updateById(any(ApCodingUserStat.class));
    }

    @Test
    @DisplayName("tag_stats 解析失败：按空分布重建，不抛异常")
    void testMergeBadJsonRebuilds() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setTagStats("not-json");
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveAnswer(USER_ID, pool(), "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getTagStats().contains("\"Java\""));
    }

    @Test
    @DisplayName("作答无标签：领域分布原样保留")
    void testNoTagsKeepsExisting() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        ApCodingDailyPool noTags = pool();
        noTags.setTags(null);
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setTagStats("{\"Redis\":{\"total\":3,\"levelSum\":10}}");
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveAnswer(USER_ID, noTags, "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals("{\"Redis\":{\"total\":3,\"levelSum\":10}}", captor.getValue().getTagStats());
    }

    @Test
    @DisplayName("anyString 兜底：作答文本原样透传（含换行）")
    void testAnswerTextPassthrough() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveAnswer(USER_ID, pool(), "第一行\n第二行", null, result(null));

        ArgumentCaptor<ApCodingAnswerRecord> captor = ArgumentCaptor.forClass(ApCodingAnswerRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals("第一行\n第二行", captor.getValue().getUserAnswer());
        assertNull(captor.getValue().getLevel());
    }

    @Test
    @DisplayName("anyString：方向为空时不覆盖已有方向")
    void testDirectionNotOverwrittenWhenBlank() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        ApCodingDailyPool blankDirection = pool();
        blankDirection.setDirection(" ");
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setDirection("MySQL");
        stat.setTotalCount(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveAnswer(USER_ID, blankDirection, "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals("MySQL", captor.getValue().getDirection());
    }

    @Test
    @DisplayName("anyString：方向随作答更新（用户换了方向偏好）")
    void testDirectionUpdatedOnAnswer() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setDirection("MySQL");
        stat.setTotalCount(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveAnswer(USER_ID, pool(), "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals("Java 后端", captor.getValue().getDirection());
    }

    @Test
    @DisplayName("anyString：末次与首次答题日期正确维护")
    void testFirstAndLastAnswerDate() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveAnswer(USER_ID, pool(), "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(captor.capture());
        assertEquals(TODAY, captor.getValue().getFirstAnswerDate());
        assertEquals(TODAY, captor.getValue().getLastAnswerDate());
    }

    @Test
    @DisplayName("anyString：已存在统计只更新 lastAnswerDate，不动 firstAnswerDate")
    void testExistingStatKeepsFirstDate() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setFirstAnswerDate(Date.valueOf(LocalDate.now().minusDays(3)));
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveAnswer(USER_ID, pool(), "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals(Date.valueOf(LocalDate.now().minusDays(3)), captor.getValue().getFirstAnswerDate());
        assertEquals(TODAY, captor.getValue().getLastAnswerDate());
    }



    @Test
    @DisplayName("anyString：未评估时 covered/missing 也照常落库")
    void testPendingStillStoresJson() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        CodingDailyEvaluator.Result pending = new CodingDailyEvaluator.Result();
        pending.pending = true;
        pending.covered = List.of();
        pending.missing = List.of();

        service.saveAnswer(USER_ID, pool(), "作答", null, pending);

        ArgumentCaptor<ApCodingAnswerRecord> captor = ArgumentCaptor.forClass(ApCodingAnswerRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals("[]", captor.getValue().getCovered());
        assertEquals("[]", captor.getValue().getMissing());
        assertNull(captor.getValue().getLevel());
    }

    @Test
    @DisplayName("anyString：作答与统计的 level 口径一致")
    void testLevelConsistencyBetweenRecordAndStat() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveAnswer(USER_ID, pool(), "作答", null, result(2));

        ArgumentCaptor<ApCodingAnswerRecord> rec = ArgumentCaptor.forClass(ApCodingAnswerRecord.class);
        verify(recordMapper).insert(rec.capture());
        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        assertEquals(2, rec.getValue().getLevel());
        assertTrue(stat.getValue().getTagStats().contains("\"levelSum\":2"));
    }

    @Test
    @DisplayName("anyString：多标签同题各自累计一份等级和")
    void testLevelSumAccumulatedPerTag() {
        givenNoDailyRecord();
        when(recordMapper.insert(any(ApCodingAnswerRecord.class))).thenReturn(1);
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setTagStats("{\"Java\":{\"total\":1,\"levelSum\":3},\"并发\":{\"total\":1,\"levelSum\":3}}");
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveAnswer(USER_ID, pool(), "作答", null, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        String tagStats = captor.getValue().getTagStats();
        assertTrue(tagStats.contains("\"levelSum\":7"));
    }
}
