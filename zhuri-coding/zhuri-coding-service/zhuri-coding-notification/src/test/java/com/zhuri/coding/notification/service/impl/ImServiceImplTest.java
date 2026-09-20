package com.zhuri.coding.notification.service.impl;

import com.zhuri.coding.apis.article.IFollowClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.ImMessageDto;
import com.zhuri.coding.model.notification.dtos.ImReadDto;
import com.zhuri.coding.model.notification.pojos.ImMessage;
import com.zhuri.coding.model.notification.pojos.ImSession;
import com.zhuri.coding.notification.mapper.ImMessageMapper;
import com.zhuri.coding.notification.mapper.ImSessionMapper;
import com.zhuri.coding.notification.service.ImStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ImServiceImpl 单元测试
 *
 * 覆盖会话列表、获取/创建会话、消息列表、发送消息（权限状态机判定 + 长文本截断 +
 * 会话未读数递增）、消息已读（未读数清零 + isActive 置位）等核心分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImServiceImpl 私信服务")
class ImServiceImplTest {

    @Mock
    private ImSessionMapper imSessionMapper;
    @Mock
    private ImMessageMapper imMessageMapper;
    @Mock
    private ImStateMachine imStateMachine;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private IUserClient userClient;
    @Mock
    private IFollowClient followClient;

    @InjectMocks
    private ImServiceImpl imService;

    /** 按 user1=小ID, user2=大ID 构造会话 */
    private ImSession session(Long id, Long user1, Long user2, Integer isActive, Integer unread) {
        ImSession s = new ImSession();
        s.setId(id);
        s.setUser1Id(user1);
        s.setUser2Id(user2);
        s.setIsActive(isActive);
        s.setUser1UnreadCount(unread);
        s.setUser2UnreadCount(unread);
        s.setSessionKey(user1 + "_" + user2);
        return s;
    }

    private Map<String, Object> peerInfoData(String name, String avatar) {
        Map<String, Object> data = new HashMap<>();
        data.put("nickname", name);
        data.put("avatar", avatar);
        return data;
    }

    /** 通过反射将指定字段置为 null，模拟 @Autowired(required=false) 依赖缺失 */
    private void clearField(String name) throws Exception {
        Field f = ImServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(imService, null);
    }

    @Nested
    @DisplayName("listSessions 会话列表")
    class ListSessions {
        @Test
        @DisplayName("正常：对方信息/us读/权限补齐")
        void testOk() {
            ImSession s = session(10L, 100L, 200L, 1, 3);
            when(imSessionMapper.selectByUserId(100L)).thenReturn(List.of(s));
            when(userClient.getPublicInfo(200L)).thenReturn(ResponseResult.okResult(peerInfoData("张三", "a.png")));

            Map<String, Object> data = (Map<String, Object>) imService.listSessions(100L).getData();
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            assertEquals(1, list.size());
            Map<String, Object> item = list.get(0);
            assertEquals(200L, item.get("peer_id"));
            assertEquals("张三", item.get("peer_name"));
            assertEquals(3, item.get("unread_count"));
            // is_active=1 → can_send_unlimited=true
            assertTrue((Boolean) item.get("can_send_unlimited"));
        }

        @Test
        @DisplayName("userClient 未注入 → 对方昵称/头像空串兜底")
        void testNoUserClient() throws Exception {
            clearField("userClient");
            ImSession s = session(10L, 100L, 200L, 0, 0);
            when(imSessionMapper.selectByUserId(100L)).thenReturn(List.of(s));

            Map<String, Object> data = (Map<String, Object>) imService.listSessions(100L).getData();
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            assertEquals("", list.get(0).get("peer_name"));
            assertEquals("", list.get(0).get("peer_avatar"));
        }
    }

    @Nested
    @DisplayName("getOrCreateSession 获取或创建会话")
    class GetOrCreateSession {
        @Test
        @DisplayName("peerId 为空 → 参数错误")
        void testNullPeer() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), imService.getOrCreateSession(100L, null).getCode());
        }

        @Test
        @DisplayName("peerId 等于自己 → 参数错误")
        void testSelfPeer() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), imService.getOrCreateSession(100L, 100L).getCode());
        }

        @Test
        @DisplayName("会话已存在 → 直接返回")
        void testExisting() {
            ImSession s = session(10L, 100L, 200L, 1, 2);
            when(imSessionMapper.selectBySessionKey("100_200")).thenReturn(s);
            when(userClient.getPublicInfo(200L)).thenReturn(ResponseResult.okResult(peerInfoData("张三", "a")));

            Map<String, Object> data = (Map<String, Object>) imService.getOrCreateSession(100L, 200L).getData();
            assertEquals(10L, data.get("session_id"));
            assertEquals(200L, data.get("peer_id"));
            verify(imSessionMapper, never()).insert(any(ImSession.class));
        }

        @Test
        @DisplayName("会话不存在 → 创建新会话并返回")
        void testCreate() {
            when(imSessionMapper.selectBySessionKey("100_200")).thenReturn(null);
            when(imSessionMapper.insert(any(ImSession.class))).thenReturn(1);
            when(userClient.getPublicInfo(200L)).thenReturn(ResponseResult.okResult(peerInfoData("李四", "b")));

            imService.getOrCreateSession(100L, 200L);
            // insert 时用户1=min(100,200)=100, 用户2=200
            verify(imSessionMapper).insert(ArgumentMatchers.<ImSession>argThat(s -> s.getUser1Id() == 100L && s.getUser2Id() == 200L));
        }

        @Test
        @DisplayName("并发插入撞唯一索引 → 捕获 DuplicateKeyException 回读既有会话")
        void testCreateDuplicate() {
            when(imSessionMapper.selectBySessionKey("100_200"))
                    .thenReturn(null)                          // 第一次查无既有会话
                    .thenReturn(session(10L, 100L, 200L, 1, 0)); // 冲突后回读到的既有会话
            when(imSessionMapper.insert(any(ImSession.class)))
                    .thenThrow(new DuplicateKeyException("uk_session_key dup"));
            when(userClient.getPublicInfo(200L)).thenReturn(ResponseResult.okResult(peerInfoData("李四", "b")));

            imService.getOrCreateSession(100L, 200L);
            // 未抛异常，返回的是回读的既有会话
            verify(imSessionMapper, times(2)).selectBySessionKey("100_200");
        }
    }

    @Nested
    @DisplayName("getPeerUserId 会话归属校验")
    class GetPeerUserId {
        @Test
        @DisplayName("会话不存在 → 返回 null")
        void testSessionMissing() {
            when(imSessionMapper.selectById(10L)).thenReturn(null);
            assertNull(imService.getPeerUserId(10L, 100L));
        }

        @Test
        @DisplayName("会话成员 → 返回对方 id")
        void testMember() {
            when(imSessionMapper.selectById(10L)).thenReturn(session(10L, 100L, 200L, 1, 0));
            assertEquals(200L, imService.getPeerUserId(10L, 100L));
        }

        @Test
        @DisplayName("非会话成员（越权） → 返回 null")
        void testNonMember() {
            when(imSessionMapper.selectById(10L)).thenReturn(session(10L, 100L, 200L, 1, 0));
            assertNull(imService.getPeerUserId(10L, 300L));
        }
    }

    @Nested
    @DisplayName("listMessages 消息列表")
    class ListMessages {
        @Test
        @DisplayName("正常：倒序返回并计算 next_cursor")
        void testOk() {
            when(imSessionMapper.selectById(10L)).thenReturn(session(10L, 100L, 200L, 1, 0));
            ImMessage m1 = msg(1L, 100L, 200L, "hi");
            ImMessage m2 = msg(2L, 100L, 200L, "yo");
            // 返回顺序 [m1, m2]，服务端会 reverse 成 [m2, m1]；size=2 → 条数==limit → hasMore=true
            when(imMessageMapper.selectBySessionId(10L, 5L, 2)).thenReturn(new ArrayList<>(List.of(m1, m2)));

            Map<String, Object> data = (Map<String, Object>) imService.listMessages(200L, 10L, 5L, 2).getData();
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            assertEquals(2, list.size());
            assertEquals(1L, list.get(1).get("id"));
            // is_self: sender=100, viewer=200 → false
            assertFalse((Boolean) list.get(0).get("is_self"));
            // has_more: size==limit 且非空 → true
            assertEquals(true, data.get("has_more"));
        }

        @Test
        @DisplayName("size 为空 → 默认 20")
        void testDefaultSize() {
            when(imSessionMapper.selectById(10L)).thenReturn(session(10L, 100L, 200L, 1, 0));
            when(imMessageMapper.selectBySessionId(10L, null, 20)).thenReturn(List.of());

            Map<String, Object> data = (Map<String, Object>) imService.listMessages(100L, 10L, null, null).getData();
            assertEquals(false, data.get("has_more"));
        }

        @Test
        @DisplayName("非会话成员 → 无权访问（3000）")
        void testNonMember() {
            when(imSessionMapper.selectById(10L)).thenReturn(session(10L, 100L, 200L, 1, 0));
            ResponseResult r = imService.listMessages(300L, 10L, null, null);
            assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
            verify(imMessageMapper, never()).selectBySessionId(anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("sendMessage 发送消息")
    class SendMessage {
        private ImMessageDto dto(Long receiver, String content) {
            ImMessageDto d = new ImMessageDto();
            d.setReceiverId(receiver);
            d.setContent(content);
            return d;
        }

        @Test
        @DisplayName("接收方为空 → 参数错误")
        void testNullReceiver() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), imService.sendMessage(100L, dto(null, "hi")).getCode());
        }

        @Test
        @DisplayName("接收方为自己 → 参数错误")
        void testSelfReceiver() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), imService.sendMessage(100L, dto(100L, "hi")).getCode());
        }

        @Test
        @DisplayName("内容为空 → 参数错误")
        void testBlankContent() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), imService.sendMessage(100L, dto(200L, "  ")).getCode());
        }

        @Test
        @DisplayName("已达发送限制 → 403 业务错误")
        void testLimitReached() {
            when(imSessionMapper.selectBySessionKey("100_200"))
                    .thenReturn(session(10L, 100L, 200L, 0, 0));
            when(imStateMachine.checkPermission(100L, 200L, session(10L, 100L, 200L, 0, 0)))
                    .thenReturn(ImStateMachine.SendPermission.LIMIT_REACHED);

            ResponseResult r = imService.sendMessage(100L, dto(200L, "hi"));
            assertEquals(403, r.getCode());
            verify(imMessageMapper, never()).insert(any(ImMessage.class));
        }

        @Test
        @DisplayName("成功：新建会话、写消息、更新会话未读")
        void testOk() {
            when(imSessionMapper.selectBySessionKey("100_200")).thenReturn(null);
            when(imStateMachine.checkPermission(eq(100L), eq(200L), any(ImSession.class)))
                    .thenReturn(ImStateMachine.SendPermission.ALLOWED);

            Map<String, Object> data = (Map<String, Object>) imService.sendMessage(100L, dto(200L, "hello")).getData();
            assertEquals("sent", data.get("status"));
            // 新建了会话（receiver user2 未读+1）
            verify(imSessionMapper).insert(any(ImSession.class));
            verify(imMessageMapper).insert(ArgumentMatchers.<ImMessage>argThat(m -> m.getSenderId() == 100L && m.getReceiverId() == 200L));
            verify(imSessionMapper).updateById(ArgumentMatchers.<ImSession>argThat(s -> s.getUser2UnreadCount() == 1));
        }

        @Test
        @DisplayName("超长内容 → 会话预览截断 50 字")
        void testTruncate() {
            String longText = "x".repeat(80);
            ImSession s = session(10L, 100L, 200L, 0, 0);
            when(imSessionMapper.selectBySessionKey("100_200")).thenReturn(s);
            when(imStateMachine.checkPermission(eq(100L), eq(200L), any(ImSession.class)))
                    .thenReturn(ImStateMachine.SendPermission.ALLOWED);

            imService.sendMessage(100L, dto(200L, longText));

            verify(imSessionMapper).updateById(ArgumentMatchers.<ImSession>argThat(sv -> sv.getLastMessage().endsWith("...")));
        }
    }

    @Nested
    @DisplayName("markRead 标记已读")
    class MarkRead {
        @Test
        @DisplayName("sessionId 为空 → 参数错误")
        void testNullSession() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), imService.markRead(200L, new ImReadDto()).getCode());
        }

        @Test
        @DisplayName("正常：清零未读并激活会话")
        void testOk() {
            ImReadDto dto = new ImReadDto();
            dto.setSessionId(10L);
            ImSession s = session(10L, 100L, 200L, 0, 3);
            when(imSessionMapper.selectById(10L)).thenReturn(s);

            ResponseResult r = imService.markRead(200L, dto);
            assertEquals(200, r.getCode());
            verify(imMessageMapper).markRead(10L, Long.MAX_VALUE, 200L);
            // viewer=user2 → user2UnreadCount 清零；isActive 置 1
            verify(imSessionMapper).updateById(ArgumentMatchers.<ImSession>argThat(sv -> sv.getUser2UnreadCount() == 0 && sv.getIsActive() == 1));
        }

        @Test
        @DisplayName("非会话成员 → 无权操作（3000），不触达消息与会话")
        void testNonMember() {
            ImReadDto dto = new ImReadDto();
            dto.setSessionId(10L);
            dto.setLastReadId(5L);
            when(imSessionMapper.selectById(10L)).thenReturn(session(10L, 100L, 200L, 0, 3));

            ResponseResult r = imService.markRead(300L, dto);
            assertEquals(AppHttpCodeEnum.NO_OPERATOR_AUTH.getCode(), r.getCode());
            verify(imMessageMapper, never()).markRead(anyLong(), anyLong(), anyLong());
            verify(imSessionMapper, never()).updateById(any(ImSession.class));
        }
    }

    private ImMessage msg(Long id, Long sender, Long receiver, String content) {
        ImMessage m = new ImMessage();
        m.setId(id);
        m.setSessionId(10L);
        m.setSenderId(sender);
        m.setReceiverId(receiver);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }
}