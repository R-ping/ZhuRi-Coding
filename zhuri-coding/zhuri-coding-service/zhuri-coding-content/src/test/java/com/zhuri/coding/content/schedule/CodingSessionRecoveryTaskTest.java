package com.zhuri.coding.content.schedule;

import com.zhuri.coding.content.mapper.coding.ApCodingAssessmentMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingInterviewMapper;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coding 场次滞留收尾任务单元测试。
 *
 * 覆盖：两侧都发起条件更新且带正的批量上限、单侧异常不拖累另一侧、无滞留场次时静默返回。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Coding 场次滞留收尾任务单元测试")
class CodingSessionRecoveryTaskTest {

    @Mock
    private ApCodingInterviewMapper interviewMapper;
    @Mock
    private ApCodingAssessmentMapper assessmentMapper;

    @InjectMocks
    private CodingSessionRecoveryTask task;

    @Test
    @DisplayName("收尾 - 面试与测评两侧都按批量上限发起条件更新")
    void testExpireBothSides() {
        when(interviewMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(3);
        when(assessmentMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(2);

        task.expireStaleSessions();

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(interviewMapper).expireStaleOngoing(any(Date.class), limit.capture());
        verify(assessmentMapper).expireStaleOngoing(any(Date.class), anyInt());
        // 批量上限必须为正：为 0 时 SQL 变成 LIMIT 0，任务会静默空转（字段默认值就是防这个）
        assertTrue(limit.getValue() > 0, "批量上限应为正值");
    }

    @Test
    @DisplayName("收尾 - 面试侧异常不外抛，且不影响测评侧收尾")
    void testInterviewFailureDoesNotBlockAssessment() {
        when(interviewMapper.expireStaleOngoing(any(Date.class), anyInt()))
            .thenThrow(new RuntimeException("db down"));
        when(assessmentMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(1);

        assertDoesNotThrow(() -> task.expireStaleSessions());

        // 关键：一张表故障不该让另一张表这一轮停止收尾
        verify(assessmentMapper).expireStaleOngoing(any(Date.class), anyInt());
    }

    @Test
    @DisplayName("收尾 - 没有滞留场次时静默返回，不产生异常")
    void testNothingToDo() {
        when(interviewMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(0);
        when(assessmentMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(0);

        assertDoesNotThrow(() -> task.expireStaleSessions());
    }
}
