package com.zhuri.coding.content.service.coding;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingDailyPoolMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodingAnswerTxService 单元测试（每日一题 · 简答落库事务）
 *
 * 覆盖：开始作答落「未作答」流水（含并发重入撞唯一键）、换题改指向、提交的两段
 * （第一段 CAS 抢下作答并计一次「被做过」、并发只放行一次；第二段补写评估与统计）、
 * 未评估与已评估两种落库口径、领域分布按标签累计（含单题最多 3 个标签）、
 * 方向随作答落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodingAnswerTxService 简答落库事务")
class CodingAnswerTxServiceTest {

    private static final Integer USER_ID = 1001;
    private static final Date TODAY = Date.valueOf(LocalDate.now());
    /** SET 子句里参数占位名的形状：{@code #{ew.paramNameValuePairs.MPGENVAL1}} */
    private static final Pattern SET_PARAM = Pattern.compile("(MPGENVAL\\d+)");

    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;
    @Mock
    private ApCodingDailyPoolMapper poolMapper;

    @InjectMocks
    private CodingAnswerTxService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper.set(...) 会立即解析列名，需要 MyBatis-Plus 的表缓存；
        // 纯单测没有 Mapper 扫描，这里手动初始化一次（与 ActivityLifecycleTaskTest 同做法）。
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCodingAnswerRecord.class);
    }

    // ---------- 辅助 ----------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LambdaUpdateWrapper<ApCodingAnswerRecord> capturedUpdate(int index) {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(recordMapper, atLeastOnce()).update(any(), captor.capture());
        return captor.getAllValues().get(index);
    }

    /** 取第 index 次 UPDATE 的 SET 列名子句 */
    private String setClause(int index) {
        return capturedUpdate(index).getSqlSet();
    }

    /**
     * 取第 index 次 UPDATE 真正写进 SET 的值。
     *
     * <p>两段都写 {@code update(null, wrapper)}，写进去的值不落在实体上，只在 wrapper 里；
     * 而 {@code eq(...)} 的条件值也混在同一个 map 中，所以要按 SET 子句里的占位名回捞。</p>
     */
    private List<Object> setValues(int index) {
        LambdaUpdateWrapper<ApCodingAnswerRecord> wrapper = capturedUpdate(index);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        List<Object> values = new ArrayList<>();
        Matcher matcher = SET_PARAM.matcher(wrapper.getSqlSet());
        while (matcher.find()) {
            values.add(params.get(matcher.group(1)));
        }
        return values;
    }

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

    /** 点了「开始作答」后落下的流水：指向某道题，还没提交 */
    private ApCodingAnswerRecord issued() {
        ApCodingAnswerRecord r = new ApCodingAnswerRecord();
        r.setId(900L);
        r.setUserId(USER_ID);
        r.setPoolId(5L);
        r.setAnswerDate(TODAY);
        r.setUserAnswer("");
        r.setScoreAwarded(0);
        r.setStatus(ApCodingAnswerRecord.STATUS_ISSUED);
        return r;
    }

    /** 第一段 CAS 命中一行（第二段回写也命中） */
    private void givenIssued() {
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(issued());
        when(recordMapper.update(any(), any())).thenReturn(1);
    }

    // ==================== 第一段：CAS 抢下作答 ====================

    @Test
    @DisplayName("claimAnswer - 未作答：CAS 写入答案与用时、状态推到已作答，并计一次「被做过」")
    void testClaimAnswerWins() {
        when(recordMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.claimAnswer(900L, 5L, "第一行\n第二行", 12));

        String clause = setClause(0);
        assertTrue(clause.contains("user_answer"));
        assertTrue(clause.contains("elapsed_seconds"));
        assertTrue(clause.contains("status"));

        List<Object> sets = setValues(0);
        assertTrue(sets.contains("第一行\n第二行"));
        assertTrue(sets.contains(12));
        assertTrue(sets.contains(ApCodingAnswerRecord.STATUS_ANSWERED));
        // 轮转计数跟着「确认作答」走：抽题和换题都不加
        verify(poolMapper).incrementUseCount(5L);
    }

    @Test
    @DisplayName("claimAnswer - CAS 影响 0 行（已作答 / 并发输家）：返回 false，不计数也不碰统计")
    void testClaimAnswerLoses() {
        when(recordMapper.update(any(), any())).thenReturn(0);

        assertTrue(!service.claimAnswer(900L, 5L, "作答", 10));
        verify(poolMapper, never()).incrementUseCount(any());
        verify(statMapper, never()).insert(any(ApCodingUserStat.class));
    }

    // ==================== 第二段：补写评估 ====================

    @Test
    @DisplayName("saveEvaluation - 已评估：等级/点评/考点清单写回，统计累计等级")
    void testSaveEvaluationEvaluated() {
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        List<Object> sets = setValues(0);
        assertTrue(sets.contains(4));
        assertTrue(sets.contains("[\"可见性\"]"));
        assertTrue(sets.contains("[]"));
        assertTrue(sets.contains("讲到了可见性"));

        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        assertEquals("Java 后端", stat.getValue().getDirection());
        assertEquals(1, stat.getValue().getTotalCount());
        assertTrue(stat.getValue().getTagStats().contains("levelSum"));
    }

    @Test
    @DisplayName("saveEvaluation - 未评估：等级写空，统计只加 total 不加 levelSum")
    void testSaveEvaluationPending() {
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(null));

        List<Object> sets = setValues(0);
        assertTrue(sets.contains(null));
        assertTrue(!sets.contains(4));

        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        assertEquals(1, stat.getValue().getTotalCount());
        assertTrue(stat.getValue().getTagStats().contains("total"));
        assertTrue(!stat.getValue().getTagStats().contains("levelSum"));
    }

    @Test
    @DisplayName("saveEvaluation - 回写未命中流水（update 0 行）：统计照加，不抛")
    void testSaveEvaluationMissTolerated() {
        when(recordMapper.update(any(), any())).thenReturn(0);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        verify(statMapper).insert(any(ApCodingUserStat.class));
    }

    // ==================== 开始作答落库 ====================

    @Test
    @DisplayName("issueQuestion - 开始作答即落库：status 未作答、答案为空串，不动轮转计数")
    void testIssueQuestion() {
        ApCodingAnswerRecord rec = service.issueQuestion(USER_ID, TODAY, 5L);

        assertEquals(USER_ID, rec.getUserId());
        assertEquals(5L, rec.getPoolId());
        assertEquals(TODAY, rec.getAnswerDate());
        assertEquals("", rec.getUserAnswer());
        assertEquals(ApCodingAnswerRecord.STATUS_ISSUED, rec.getStatus().intValue());
        assertTrue(!ApCodingAnswerRecord.isAnswered(rec));
        verify(recordMapper).insert(rec);
        // 计数只在确认作答时加：换题反复抽不该把池子里的计数推高
        verify(poolMapper, never()).incrementUseCount(any());
    }

    @Test
    @DisplayName("issueQuestion - 并发重入撞唯一键：回读返回已存在那条")
    void testIssueQuestionRace() {
        ApCodingAnswerRecord existing = issued();
        existing.setPoolId(7L);
        when(recordMapper.insert(any(ApCodingAnswerRecord.class)))
            .thenThrow(new DuplicateKeyException("dup"));
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ApCodingAnswerRecord rec = service.issueQuestion(USER_ID, TODAY, 5L);

        assertEquals(7L, rec.getPoolId());
        verify(poolMapper, never()).incrementUseCount(any());
    }

    // ==================== 换题重新开始 ====================

    @Test
    @DisplayName("reissueQuestion - 未作答：只改指向的题，不计数")
    void testReissueWhenIssued() {
        when(recordMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.reissueQuestion(900L, 6L));
        verify(poolMapper, never()).incrementUseCount(any());
    }

    @Test
    @DisplayName("reissueQuestion - 已作答：UPDATE 不命中，不改也不计数")
    void testReissueWhenAnswered() {
        when(recordMapper.update(any(), any())).thenReturn(0);

        assertTrue(!service.reissueQuestion(900L, 6L));
        verify(poolMapper, never()).incrementUseCount(any());
    }

    // ==================== 统计口径 ====================

    @Test
    @DisplayName("领域分布合并：单题最多取前 3 个标签")
    void testMergeTagLimitsToThree() {
        ApCodingDailyPool manyTags = pool();
        manyTags.setTags("A,B,C,D,E");
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, manyTags, TODAY, result(4));

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
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(null)                       // 第一次读：不存在
            .thenReturn(new ApCodingUserStat());    // 撞唯一键回读：存在
        when(statMapper.insert(any(ApCodingUserStat.class)))
            .thenThrow(new DuplicateKeyException("dup"));

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        verify(statMapper).updateById(any(ApCodingUserStat.class));
    }

    @Test
    @DisplayName("tag_stats 解析失败：按空分布重建，不抛异常")
    void testMergeBadJsonRebuilds() {
        givenIssued();
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setTagStats("not-json");
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getTagStats().contains("\"Java\""));
    }

    @Test
    @DisplayName("作答无标签：领域分布原样保留")
    void testNoTagsKeepsExisting() {
        givenIssued();
        ApCodingDailyPool noTags = pool();
        noTags.setTags(null);
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setTagStats("{\"Redis\":{\"total\":3,\"levelSum\":10}}");
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveEvaluation(USER_ID, 900L, noTags, TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals("{\"Redis\":{\"total\":3,\"levelSum\":10}}", captor.getValue().getTagStats());
    }

    @Test
    @DisplayName("第二段只补写评估字段，不碰答案与状态（那两样在第一段落好了）")
    void testEvaluationDoesNotTouchAnswer() {
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(null));

        String clause = setClause(0);
        assertTrue(clause.contains("level"));
        assertTrue(clause.contains("feedback"));
        assertTrue(clause.contains("covered"));
        assertTrue(clause.contains("missing"));
        assertTrue(!clause.contains("user_answer"));
        assertTrue(!clause.contains("status"));
        assertEquals(4, setValues(0).size());
    }

    @Test
    @DisplayName("方向为空时不覆盖已有方向")
    void testDirectionNotOverwrittenWhenBlank() {
        givenIssued();
        ApCodingDailyPool blankDirection = pool();
        blankDirection.setDirection(" ");
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setDirection("MySQL");
        stat.setTotalCount(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveEvaluation(USER_ID, 900L, blankDirection, TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals("MySQL", captor.getValue().getDirection());
    }

    @Test
    @DisplayName("方向随作答更新（用户换了方向偏好）")
    void testDirectionUpdatedOnAnswer() {
        givenIssued();
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setDirection("MySQL");
        stat.setTotalCount(1);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals("Java 后端", captor.getValue().getDirection());
    }

    @Test
    @DisplayName("末次与首次答题日期正确维护")
    void testFirstAndLastAnswerDate() {
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(captor.capture());
        assertEquals(TODAY, captor.getValue().getFirstAnswerDate());
        assertEquals(TODAY, captor.getValue().getLastAnswerDate());
    }

    @Test
    @DisplayName("已存在统计只更新 lastAnswerDate，不动 firstAnswerDate")
    void testExistingStatKeepsFirstDate() {
        givenIssued();
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setFirstAnswerDate(Date.valueOf(LocalDate.now().minusDays(3)));
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals(Date.valueOf(LocalDate.now().minusDays(3)), captor.getValue().getFirstAnswerDate());
        assertEquals(TODAY, captor.getValue().getLastAnswerDate());
    }



    @Test
    @DisplayName("未评估时 covered/missing 也照常落库")
    void testPendingStillStoresJson() {
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        CodingDailyEvaluator.Result pending = new CodingDailyEvaluator.Result();
        pending.pending = true;
        pending.comment = CodingDailyEvaluator.PENDING_COMMENT;
        pending.covered = List.of();
        pending.missing = List.of();

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, pending);

        List<Object> sets = setValues(0);
        // covered 与 missing 各写一次空数组，评语照写；等级写空
        assertTrue(sets.contains("[]"));
        assertTrue(sets.contains(CodingDailyEvaluator.PENDING_COMMENT));
        assertTrue(sets.contains(null));
    }

    @Test
    @DisplayName("作答与统计的 level 口径一致")
    void testLevelConsistencyBetweenRecordAndStat() {
        givenIssued();
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(2));

        ArgumentCaptor<ApCodingUserStat> stat = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).insert(stat.capture());
        assertTrue(setValues(0).contains(2));
        assertTrue(stat.getValue().getTagStats().contains("\"levelSum\":2"));
    }

    @Test
    @DisplayName("多标签同题各自累计一份等级和")
    void testLevelSumAccumulatedPerTag() {
        givenIssued();
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(1);
        stat.setTagStats("{\"Java\":{\"total\":1,\"levelSum\":3},\"并发\":{\"total\":1,\"levelSum\":3}}");
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat);

        service.saveEvaluation(USER_ID, 900L, pool(), TODAY, result(4));

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        String tagStats = captor.getValue().getTagStats();
        assertTrue(tagStats.contains("\"levelSum\":7"));
    }
}
