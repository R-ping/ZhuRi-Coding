package com.zhuri.coding.content.schedule;

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
 * Coding 面试场次滞留收尾任务单元测试。
 *
 * 覆盖：发起条件更新且带正的批量上限、异常不外抛、无滞留场次时静默返回。
 * 测评侧的收尾已随测评整层一并移除。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Coding 面试场次滞留收尾任务单元测试")
class CodingSessionRecoveryTaskTest {

    @Mock
    private ApCodingInterviewMapper interviewMapper;

    @InjectMocks
    private CodingSessionRecoveryTask task;

    @Test
    @DisplayName("收尾 - 按批量上限发起条件更新")
    void testExpireWithPositiveLimit() {
        when(interviewMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(3);

        task.expireStaleSessions();

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(interviewMapper).expireStaleOngoing(any(Date.class), limit.capture());
        // 批量上限必须为正：为 0 时 SQL 变成 LIMIT 0，任务会静默空转（字段默认值就是防这个）
        assertTrue(limit.getValue() > 0, "批量上限应为正值");
    }

    @Test
    @DisplayName("收尾 - 异常不外抛")
    void testFailureDoesNotThrow() {
        when(interviewMapper.expireStaleOngoing(any(Date.class), anyInt()))
            .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task.expireStaleSessions());
    }

    @Test
    @DisplayName("收尾 - 没有滞留场次时静默返回，不产生异常")
    void testNothingToDo() {
        when(interviewMapper.expireStaleOngoing(any(Date.class), anyInt())).thenReturn(0);

        assertDoesNotThrow(() -> task.expireStaleSessions());
    }
}
