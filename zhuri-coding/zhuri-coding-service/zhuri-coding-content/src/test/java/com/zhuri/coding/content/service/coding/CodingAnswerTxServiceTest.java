package com.zhuri.coding.content.service.coding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodingAnswerTxService 单元测试（作答落库事务服务）
 *
 * 覆盖：当日题重复提交防重、首次落库（记录+题目计数+统计）、统计首插并发兜底、
 * 练习不累加题目热度。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("作答落库事务服务单元测试")
class CodingAnswerTxServiceTest {

    private static final Integer USER_ID = 1001;

    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingQuestionMapper questionMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;

    @InjectMocks
    private CodingAnswerTxService service;

    private ApCodingQuestion question() {
        ApCodingQuestion q = new ApCodingQuestion();
        q.setId(9L);
        q.setStem("以下关于 Redis 缓存穿透的描述，哪一项是正确的？");
        q.setQuestionType(ApCodingQuestion.TYPE_SINGLE);
        q.setOptions("[\"A\",\"B\",\"C\",\"D\"]");
        q.setAnswer("[0]");
        q.setTags("Redis,缓存");
        q.setStatus(ApCodingQuestion.STATUS_PUBLISHED);
        return q;
    }

    private ApCodingUserStat stat() {
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setId(1L);
        stat.setUserId(USER_ID);
        stat.setTotalCount(2);
        stat.setCorrectCount(1);
        stat.setPracticeCount(3);
        stat.setPracticeCorrectCount(2);
        return stat;
    }

    @Test
    @DisplayName("saveAnswer - 当日题重复提交：抛异常且不落库")
    void testDuplicateDailyAnswerRejected() {
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new ApCodingAnswerRecord());

        assertThrows(IllegalStateException.class,
            () -> service.saveAnswer(USER_ID, question(), "[0]", true, 10, true));
        verify(recordMapper, never()).insert(any(ApCodingAnswerRecord.class));
    }

    @Test
    @DisplayName("saveAnswer - 当日题答对：落记录 + 题目计数 + 统计 upsert")
    void testSaveDailyCorrect() {
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            ApCodingAnswerRecord record = invocation.getArgument(0);
            record.setId(100L);
            return 1;
        }).when(recordMapper).insert(any(ApCodingAnswerRecord.class));
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat());

        ApCodingAnswerRecord saved = service.saveAnswer(USER_ID, question(), "[0]", true, 12, true);

        assertNotNull(saved.getId());
        assertEquals(1, saved.getIsDaily().intValue());
        assertEquals(1, saved.getIsCorrect().intValue());
        assertEquals(12, saved.getElapsedSeconds().intValue());
        verify(questionMapper).incrementAnswerStats(9L, 1);

        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        ApCodingUserStat updated = captor.getValue();
        assertEquals(3, updated.getTotalCount().intValue());
        assertEquals(2, updated.getCorrectCount().intValue());
        assertEquals(3, updated.getPracticeCount().intValue());
        assertNotNull(updated.getLastAnswerDate());
        assertTrue(updated.getTagStats().contains("Redis"));
    }

    @Test
    @DisplayName("saveAnswer - 统计不存在：首插；并发撞唯一键回读后走更新")
    void testStatInsertConcurrentFallback() {
        // 自由练习不做当日防重，仅统计 upsert（首插撞唯一键 → 回读更新）
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, stat());
        when(statMapper.insert(any(ApCodingUserStat.class))).thenThrow(new DuplicateKeyException("uk_user"));

        service.saveAnswer(USER_ID, question(), "[1]", false, null, false);

        verify(statMapper).insert(any(ApCodingUserStat.class));
        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getPracticeCount().intValue());
        assertEquals(2, captor.getValue().getPracticeCorrectCount().intValue());
    }

    @Test
    @DisplayName("saveAnswer - 自由练习：不累加题目热度，只沉淀练习统计")
    void testPracticeDoesNotTouchQuestionStats() {
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat());

        service.saveAnswer(USER_ID, question(), "[0]", true, null, false);

        verify(questionMapper, never()).incrementAnswerStats(any(), anyInt());
        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper, times(1)).updateById(captor.capture());
        assertEquals(2, captor.getValue().getTotalCount().intValue());
        assertEquals(4, captor.getValue().getPracticeCount().intValue());
        assertEquals(3, captor.getValue().getPracticeCorrectCount().intValue());
    }

    @Test
    @DisplayName("saveAnswer - 当日题答错：记录 is_correct=0，题目计数不累加答对数")
    void testSaveDailyWrong() {
        when(recordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(statMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stat());

        ApCodingAnswerRecord saved = service.saveAnswer(USER_ID, question(), "[1]", false, 20, true);

        assertEquals(0, saved.getIsCorrect().intValue());
        verify(questionMapper).incrementAnswerStats(eq(9L), eq(0));
        ArgumentCaptor<ApCodingUserStat> captor = ArgumentCaptor.forClass(ApCodingUserStat.class);
        verify(statMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getTotalCount().intValue());
        assertEquals(1, captor.getValue().getCorrectCount().intValue());
    }
}