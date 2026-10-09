package com.zhuri.coding.content.service.coding.impl;

import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.content.constants.LevelScoreActionCode;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingDailyPoolMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingAnswerTxService;
import com.zhuri.coding.content.service.coding.CodingDailyEvaluator;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.coding.dtos.CodingDailyAnswerDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingDailyAnswerVO;
import com.zhuri.coding.model.coding.vos.CodingDailyQuestionVO;
import com.zhuri.coding.model.coding.vos.CodingStatVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingQuestionServiceImpl 单元测试（每日一题 · 简答）
 *
 * 覆盖：抽题与缓存（方向校验 / 换向重抽 / 兜底）、作答（评估通过落库并记分、
 * 评估降级照常落库、重复提交与并发锁）、统计回填与签到降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodingQuestionServiceImpl 每日一题（简答）")
class CodingQuestionServiceImplTest {

    private static final Integer USER_ID = 1001;
    private static final Date TODAY = Date.valueOf(LocalDate.now());

    @Mock
    private ApCodingDailyPoolMapper poolMapper;
    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;
    @Mock
    private CodingAnswerTxService txService;
    @Mock
    private CodingDailyEvaluator evaluator;
    @Mock
    private LevelService levelService;
    @Mock
    private IRewardClient rewardClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private CodingQuestionServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 防抖锁默认放行；需要模拟"操作过于频繁"的用例自行覆盖
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenReturn(true);
    }

    // ---------- 辅助 ----------

    private ApCodingDailyPool pool(Long id, String direction) {
        ApCodingDailyPool p = new ApCodingDailyPool();
        p.setId(id);
        p.setDirection(direction);
        p.setStem("讲讲你对 Java 内存模型的理解。");
        p.setKeyPoints("[\"可见性\",\"有序性\",\"不保证原子性\"]");
        p.setDifficulty(2);
        p.setTags("Java,并发");
        p.setStatus(ApCodingDailyPool.STATUS_ENABLED);
        p.setUseCount(0);
        return p;
    }

    private CodingDailyEvaluator.Result evaluated() {
        CodingDailyEvaluator.Result r = new CodingDailyEvaluator.Result();
        r.pending = false;
        r.structure = 4;
        r.coverageScore = 3;
        r.accuracy = 4;
        r.level = 4;
        r.covered = List.of("可见性");
        r.missing = List.of("有序性", "不保证原子性");
        r.comment = "讲到了可见性，但没展开禁止重排。";
        return r;
    }

    private ApCodingAnswerRecord record(Long poolId, Integer level) {
        ApCodingAnswerRecord r = new ApCodingAnswerRecord();
        r.setId(900L);
        r.setUserId(USER_ID);
        r.setPoolId(poolId);
        r.setAnswerDate(TODAY);
        r.setUserAnswer("我的作答");
        r.setLevel(level);
        r.setFeedback("ok");
        r.setCovered("[\"可见性\"]");
        r.setMissing("[\"有序性\",\"不保证原子性\"]");
        r.setElapsedSeconds(120);
        return r;
    }

    private void givenCacheMiss() {
        lenient().when(valueOps.get(contains("coding:daily2:"))).thenReturn(null);
    }

    private void givenStatExists(String direction) {
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setDirection(direction);
        stat.setTotalCount(3);
        stat.setTagStats("{\"Java\":{\"total\":3,\"levelSum\":10}}");
        lenient().when(statMapper.selectOne(any())).thenReturn(stat);
    }

    // ==================== 今日题 ====================

    @Nested
    @DisplayName("today 抽题")
    class Today {

        @Test
        @DisplayName("未答且缓存未命中：按方向抽题、写缓存、累加轮转计数")
        void testPickAndCache() {
            givenCacheMiss();
            givenStatExists("Java 后端");
            when(poolMapper.selectOneByDirection("Java 后端")).thenReturn(pool(5L, "Java 后端"));

            ResponseResult result = service.today(USER_ID, null);

            assertEquals(200, result.getCode().intValue());
            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) result.getData();
            assertEquals(5L, vo.getId());
            assertFalse(vo.getAnswered());
            assertNull(vo.getLevel());
            verify(valueOps).set(contains("coding:daily2:" + USER_ID + ":"), eq("5"),
                anyLong(), any(TimeUnit.class));
            verify(poolMapper).incrementUseCount(5L);
        }

        @Test
        @DisplayName("已答：回放完整结果（含等级与考点清单），不再抽题")
        void testReplayAnswered() {
            when(txService.findDailyRecord(USER_ID, TODAY)).thenReturn(record(5L, 4));
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));

            ResponseResult result = service.today(USER_ID, null);

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) result.getData();
            assertTrue(vo.getAnswered());
            assertEquals(4, vo.getLevel());
            assertEquals(List.of("可见性"), vo.getCovered());
            assertEquals(List.of("有序性", "不保证原子性"), vo.getMissing());
            assertEquals("我的作答", vo.getUserAnswer());
            verify(poolMapper, never()).selectOneByDirection(any());
        }

        @Test
        @DisplayName("缓存题与当前方向不一致：作废重抽（不能把人锁死在旧方向）")
        void testDirectionMismatchRepick() {
            lenient().when(valueOps.get(contains("coding:daily2:"))).thenReturn("5");
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            when(poolMapper.selectOneByDirection("前端")).thenReturn(pool(6L, "前端"));

            ResponseResult result = service.today(USER_ID, "前端");

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) result.getData();
            assertEquals(6L, vo.getId());
        }

        @Test
        @DisplayName("方向无题：降级到不限方向兜底，仍不断供")
        void testDirectionFallback() {
            givenCacheMiss();
            when(poolMapper.selectOneByDirection("小众方向")).thenReturn(null);
            when(poolMapper.selectOneAny()).thenReturn(pool(7L, "Java 后端"));

            ResponseResult result = service.today(USER_ID, "小众方向");

            assertEquals(200, result.getCode().intValue());
            assertEquals(7L, ((CodingDailyQuestionVO) result.getData()).getId());
        }

        @Test
        @DisplayName("池子为空：返回题库准备中")
        void testPoolEmpty() {
            givenCacheMiss();
            when(poolMapper.selectOneByDirection(anyString())).thenReturn(null);
            when(poolMapper.selectOneAny()).thenReturn(null);

            ResponseResult result = service.today(USER_ID, null);

            assertEquals(400, result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("题库准备中"));
        }
    }

    // ==================== 作答 ====================

    @Nested
    @DisplayName("answer 作答")
    class Answer {

        private CodingDailyAnswerDTO dto() {
            CodingDailyAnswerDTO dto = new CodingDailyAnswerDTO();
            dto.setPoolId(5L);
            dto.setAnswerText("JMM 定义了主内存与工作内存的抽象，volatile 保证可见性与有序性。");
            dto.setElapsedSeconds(120);
            return dto;
        }

        private void givenPool() {
            lenient().when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
        }

        @Test
        @DisplayName("入参校验：题目ID缺失 / 文本为空 / 超长")
        void testValidation() {
            givenPool();
            CodingDailyAnswerDTO empty = dto();
            empty.setAnswerText("   ");

            assertEquals(400, service.answer(USER_ID, new CodingDailyAnswerDTO()).getCode());
            assertEquals(400, service.answer(USER_ID, empty).getCode());

            CodingDailyAnswerDTO tooLong = dto();
            tooLong.setAnswerText("长".repeat(CodingDailyEvaluator.ANSWER_MAX_LENGTH + 1));
            assertEquals(400, service.answer(USER_ID, tooLong).getCode());
        }

        @Test
        @DisplayName("今日已答：拒绝且不评估不落库")
        void testAlreadyAnswered() {
            givenPool();
            when(txService.findDailyRecord(USER_ID, TODAY)).thenReturn(record(5L, 4));

            assertEquals(400, service.answer(USER_ID, dto()).getCode());
            verifyNoInteractions(evaluator, levelService);
        }

        @Test
        @DisplayName("评估通过：落库 + 回填得分 + 记逐日分")
        void testEvaluateAndSave() {
            givenPool();
            when(txService.findDailyRecord(USER_ID, TODAY)).thenReturn(null);
            when(evaluator.evaluate(any(ApCodingDailyPool.class), anyString())).thenReturn(evaluated());
            when(txService.saveAnswer(eq(USER_ID), any(ApCodingDailyPool.class),
                anyString(), any(), any(CodingDailyEvaluator.Result.class)))
                .thenReturn(record(5L, 4));
            when(levelService.recordActionWithLimit(eq(USER_ID.longValue()),
                eq(LevelScoreActionCode.ANSWER_QUESTION), anyString()))
                .thenReturn(Map.of("success", true, "score", new BigDecimal("3")));

            CodingDailyAnswerVO vo = (CodingDailyAnswerVO) service.answer(USER_ID, dto()).getData();

            assertEquals(4, vo.getLevel());
            assertEquals(3, vo.getScoreAwarded());
            assertEquals(3, vo.getCoverageScore());
            verify(recordMapper).updateById(ArgumentMatchers.<ApCodingAnswerRecord>argThat(
                r -> r.getId() == 900L && r.getScoreAwarded() == 3));
        }

        @Test
        @DisplayName("评估降级：照常落库，等级为 null，但逐日分仍给")
        void testEvaluatePending() {
            givenPool();
            when(txService.findDailyRecord(USER_ID, TODAY)).thenReturn(null);
            CodingDailyEvaluator.Result pending = new CodingDailyEvaluator.Result();
            pending.pending = true;
            pending.comment = CodingDailyEvaluator.PENDING_COMMENT;
            when(evaluator.evaluate(any(ApCodingDailyPool.class), anyString())).thenReturn(pending);
            when(txService.saveAnswer(eq(USER_ID), any(ApCodingDailyPool.class),
                anyString(), any(), any(CodingDailyEvaluator.Result.class)))
                .thenReturn(record(5L, null));
            when(levelService.recordActionWithLimit(eq(USER_ID.longValue()),
                eq(LevelScoreActionCode.ANSWER_QUESTION), anyString()))
                .thenReturn(Map.of("success", true, "score", new BigDecimal("3")));

            CodingDailyAnswerVO vo = (CodingDailyAnswerVO) service.answer(USER_ID, dto()).getData();

            assertTrue(vo.getPending());
            assertNull(vo.getLevel());
            assertEquals(3, vo.getScoreAwarded());
        }

        @Test
        @DisplayName("并发落到唯一键：转成「今日一题已作答」")
        void testDuplicateMappedToFriendlyError() {
            givenPool();
            when(txService.findDailyRecord(USER_ID, TODAY)).thenReturn(null);
            when(txService.saveAnswer(any(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("今日一题已作答"));

            assertEquals(400, service.answer(USER_ID, dto()).getCode());
            verify(levelService, never()).recordActionWithLimit(anyLong(), anyString(), anyString());
        }
    }

    // ==================== 统计 ====================

    @Nested
    @DisplayName("myStat 统计")
    class MyStat {

        @Test
        @DisplayName("领域分布 / 平均等级 / 今日等级 回填")
        void testStat() {
            givenStatExists("Java 后端");
            when(recordMapper.avgLevel(USER_ID)).thenReturn(3.5);
            when(txService.findDailyRecord(USER_ID, TODAY)).thenReturn(record(5L, 4));
            when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
                .thenReturn(ResponseResult.okResult(Map.of("continuousDays", 6)));

            CodingStatVO vo = (CodingStatVO) service.myStat(USER_ID).getData();

            assertEquals(6, vo.getContinuousDays());
            assertEquals(3, vo.getTotalCount());
            assertEquals("Java 后端", vo.getDirection());
            assertEquals(3.5, vo.getAvgLevel());
            assertEquals(4, vo.getTodayLevel());
            assertTrue(vo.getTodayAnswered());
            assertTrue(((Map<?, ?>) vo.getTagStats()).containsKey("Java"));
        }

        @Test
        @DisplayName("签到服务不可用：连续天数降级 0，不影响其余字段")
        void testStatRewardDown() {
            when(statMapper.selectOne(any())).thenReturn(null);
            when(recordMapper.avgLevel(USER_ID)).thenReturn(null);
            when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
                .thenThrow(new RuntimeException("reward down"));

            CodingStatVO vo = (CodingStatVO) service.myStat(USER_ID).getData();

            assertEquals(0, vo.getContinuousDays());
            assertEquals(0, vo.getTotalCount());
            assertNull(vo.getAvgLevel());
            assertFalse(vo.getTodayAnswered());
        }
    }
}
