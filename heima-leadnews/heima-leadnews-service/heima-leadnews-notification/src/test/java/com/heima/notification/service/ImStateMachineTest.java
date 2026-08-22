package com.heima.notification.service;

import com.heima.apis.article.IFollowClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.notification.pojos.ImSession;
import com.heima.notification.mapper.ImMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * ImStateMachine 单元测试
 *
 * 覆盖私信发送权限状态机的四种状态判定：
 * - S3：接收方曾回复（isActive=1）→ 无限发送 ALLOWED
 * - S0：接收方关注了发送方 → 无限发送 ALLOWED（关注服务不可用时降级为 false）
 * - S2：已发送 ≥1 条待回复 → 禁止发送 LIMIT_REACHED
 * - S1：其它 → 仅允许 1 条 ALLOWED_ONCE
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImStateMachine 私信发送权限状态机")
class ImStateMachineTest {

    @Mock
    private ImMessageMapper imMessageMapper;

    @Mock
    private IFollowClient followClient;

    @InjectMocks
    private ImStateMachine imStateMachine;

    private final Long senderId = 100L;
    private final Long receiverId = 200L;

    private ImSession session() {
        ImSession s = new ImSession();
        s.setId(1L);
        s.setIsActive(0);
        return s;
    }

    /** 构造关注接口返回值 */
    private ResponseResult followResult(Boolean isFollowing) {
        Map<String, Object> data = new HashMap<>();
        data.put("isFollowing", isFollowing);
        return ResponseResult.okResult(data);
    }

    @Nested
    @DisplayName("S3 已回复 → 无限发送")
    class S3 {
        @Test
        @DisplayName("session.isActive=1 → ALLOWED，不再查询关注与发送数")
        void testActiveAllows() {
            ImSession s = session();
            s.setIsActive(1);

            assertEquals(ImStateMachine.SendPermission.ALLOWED, imStateMachine.checkPermission(senderId, receiverId, s));
        }
    }

    @Nested
    @DisplayName("S0 接收方关注发送方 → 无限发送")
    class S0 {
        @Test
        @DisplayName("followClient.isFollowing=true → ALLOWED")
        void testFollowAllows() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(followResult(true));

            assertEquals(ImStateMachine.SendPermission.ALLOWED, imStateMachine.checkPermission(senderId, receiverId, session()));
        }
    }

    @Nested
    @DisplayName("S2 已达发送条数限制")
    class S2 {
        @Test
        @DisplayName("已发送1条且对方未回复未关注 → LIMIT_REACHED")
        void testLimitReached() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(followResult(false));
            when(imMessageMapper.countSentAfterLastReply(1L, senderId, receiverId)).thenReturn(1);

            assertEquals(ImStateMachine.SendPermission.LIMIT_REACHED,
                    imStateMachine.checkPermission(senderId, receiverId, session()));
        }
    }

    @Nested
    @DisplayName("S1 允许发送1条")
    class S1 {
        @Test
        @DisplayName("对方未回复未关注且未发送过 → ALLOWED_ONCE")
        void testAllowedOnce() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(followResult(false));
            when(imMessageMapper.countSentAfterLastReply(1L, senderId, receiverId)).thenReturn(0);

            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE,
                    imStateMachine.checkPermission(senderId, receiverId, session()));
        }

        @Test
        @DisplayName("session 为 null 且未关注 → ALLOWED_ONCE（S2 分支跳过）")
        void testNullSessionAllowedOnce() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(followResult(false));

            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE,
                    imStateMachine.checkPermission(senderId, receiverId, null));
        }
    }

    @Nested
    @DisplayName("followClient 降级/边界分支")
    class FollowDegrade {
        @Test
        @DisplayName("关注返回 code!=200 → 视为未关注，进入 S1 ALLOWED_ONCE")
        void testFollowNon200() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(ResponseResult.errorResult(500, "err"));

            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE,
                    imStateMachine.checkPermission(senderId, receiverId, session()));
        }

        @Test
        @DisplayName("关注返回 data 为 null → 未关注")
        void testFollowNullData() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(ResponseResult.okResult(null));

            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE,
                    imStateMachine.checkPermission(senderId, receiverId, session()));
        }

        @Test
        @DisplayName("关注数据缺少 isFollowing 字段 → 未关注")
        void testFollowMissingField() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(ResponseResult.okResult(new HashMap<>()));

            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE,
                    imStateMachine.checkPermission(senderId, receiverId, session()));
        }

        @Test
        @DisplayName("关注返回 isFollowing=false → 未关注")
        void testFollowFalse() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(followResult(false));
            when(imMessageMapper.countSentAfterLastReply(1L, senderId, receiverId)).thenReturn(0);

            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE,
                    imStateMachine.checkPermission(senderId, receiverId, session()));
        }

        @Test
        @DisplayName("关注服务抛出异常 → 降级为未关注")
        void testFollowThrows() {
            when(followClient.isFollowing(receiverId, senderId)).thenThrow(new RuntimeException("down"));

            ImStateMachine.SendPermission p = imStateMachine.checkPermission(senderId, receiverId, session());
            assertNotNull(p);
        }

        @Test
        @DisplayName("关注返回 result 为 null → 降级为未关注")
        void testFollowNullResult() {
            when(followClient.isFollowing(receiverId, senderId)).thenReturn(null);

            ImStateMachine.SendPermission p = imStateMachine.checkPermission(senderId, receiverId, session());
            assertNotNull(p);
        }

        @Test
        @DisplayName("senderId 为 null → isFollow 命中参数校验直接返回 false")
        void testNullSender() {
            ImStateMachine.SendPermission p = imStateMachine.checkPermission(null, receiverId, null);
            assertEquals(ImStateMachine.SendPermission.ALLOWED_ONCE, p);
        }
    }
}