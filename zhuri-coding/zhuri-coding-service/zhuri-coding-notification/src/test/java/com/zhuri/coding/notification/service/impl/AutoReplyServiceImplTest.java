package com.zhuri.coding.notification.service.impl;

import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.AutoReplySettingDto;
import com.zhuri.coding.model.notification.pojos.ImMessage;
import com.zhuri.coding.model.notification.pojos.ImSession;
import com.zhuri.coding.model.notification.pojos.UserAutoReply;
import com.zhuri.coding.notification.mapper.ImMessageMapper;
import com.zhuri.coding.notification.mapper.ImSessionMapper;
import com.zhuri.coding.notification.mapper.UserAutoReplyMapper;
import com.zhuri.coding.notification.service.AutoReplyContentGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("关注自动私信服务")
class AutoReplyServiceImplTest {

    @Mock
    private UserAutoReplyMapper autoReplyMapper;
    @Mock
    private ImSessionMapper imSessionMapper;
    @Mock
    private ImMessageMapper imMessageMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private IUserClient userClient;
    /** 用真实实现：文案校验是纯函数，mock 掉反而测不出规则 */
    @org.mockito.Spy
    private AutoReplyContentGuard contentGuard = new AutoReplyContentGuard();

    @InjectMocks
    private AutoReplyServiceImpl service;

    // ---------- 辅助 ----------

    private UserAutoReply config(int enabled, String effective) {
        UserAutoReply row = new UserAutoReply();
        row.setUserId(200L);
        row.setEnabled(enabled);
        row.setContent(effective);
        row.setContentVersion(1);
        row.setAuditStatus(1);
        row.setEffectiveContent(effective);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private void stubRedisNormal() {
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        // 限流走 Lua：返回值 = 当前计数
        when(stringRedisTemplate.execute(any(), anyList(), any())).thenReturn(1L);
    }

    // ==================== 配置读取 ====================

    @Test
    @DisplayName("未设置过的用户返回默认配置：开启 + 谢谢关注")
    @SuppressWarnings("unchecked")
    void testGetSettingDefault() {
        when(autoReplyMapper.selectById(100L)).thenReturn(null);

        Map<String, Object> data = (Map<String, Object>) service.getSetting(100L).getData();

        assertEquals(true, data.get("enabled"));
        assertEquals("谢谢关注", data.get("content"));
        assertEquals("谢谢关注", data.get("effective_content"));
        assertEquals(1, data.get("audit_status"));
        assertEquals(true, data.get("can_send"));
    }

    // ==================== 配置保存 ====================

    @Test
    @DisplayName("格式不合法拒绝保存：空文案")
    void testSaveBlankRejected() {
        AutoReplySettingDto dto = new AutoReplySettingDto();
        dto.setContent("   ");

        ResponseResult r = service.saveSetting(100L, dto);

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        verify(autoReplyMapper, never()).insert(any(UserAutoReply.class));
    }

    @Test
    @DisplayName("内容不合规保存但驳回，沿用默认生效文案")
    void testSavePolicyRejected() {
        when(autoReplyMapper.selectById(100L)).thenReturn(null);
        AutoReplySettingDto dto = new AutoReplySettingDto();
        dto.setContent("加微信 abc 领取资料");

        ResponseResult r = service.saveSetting(100L, dto);

        assertEquals(200, r.getCode());
        ArgumentCaptor<UserAutoReply> captor = ArgumentCaptor.forClass(UserAutoReply.class);
        verify(autoReplyMapper).insert(captor.capture());
        UserAutoReply saved = captor.getValue();
        assertEquals(2, saved.getAuditStatus());
        assertEquals("谢谢关注", saved.getEffectiveContent());
        assertTrue(saved.getAuditReason().contains("联系方式"));
    }

    @Test
    @DisplayName("合规文案保存后立即生效并升版本")
    void testSavePassed() {
        when(autoReplyMapper.selectById(100L)).thenReturn(null);
        AutoReplySettingDto dto = new AutoReplySettingDto();
        dto.setContent("感谢关注，常来玩");

        ResponseResult r = service.saveSetting(100L, dto);

        assertEquals(200, r.getCode());
        ArgumentCaptor<UserAutoReply> captor = ArgumentCaptor.forClass(UserAutoReply.class);
        verify(autoReplyMapper).insert(captor.capture());
        UserAutoReply saved = captor.getValue();
        assertEquals(1, saved.getAuditStatus());
        assertEquals("感谢关注，常来玩", saved.getEffectiveContent());
        assertEquals(2, saved.getContentVersion());
    }

    // ==================== 触发投递 ====================

    @Test
    @DisplayName("开关关闭时静默跳过，不建会话不写消息")
    void testSendDisabled() {
        when(autoReplyMapper.selectById(200L)).thenReturn(config(0, "谢谢关注"));

        service.sendFollowAutoReply(9L, 100L, 200L);

        verify(imMessageMapper, never()).insert(any(ImMessage.class));
        verify(imSessionMapper, never()).applyIncomingMessage(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("同一次关注重复投递直接跳过")
    void testSendDuplicate() {
        ImMessage exist = new ImMessage();
        exist.setId(1L);
        when(imMessageMapper.selectBySource("FOLLOW_AUTO_REPLY", 9L)).thenReturn(exist);

        service.sendFollowAutoReply(9L, 100L, 200L);

        verify(autoReplyMapper, never()).selectById(anyLong());
        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    @DisplayName("冷却窗口内不再发送（取关重关场景）")
    void testSendCooldown() {
        when(autoReplyMapper.selectById(200L)).thenReturn(config(1, "谢谢关注"));
        when(stringRedisTemplate.hasKey("im:ar:cd:200:100")).thenReturn(true);

        service.sendFollowAutoReply(9L, 100L, 200L);

        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    @DisplayName("超过每分钟上限时跳过")
    void testSendRateLimited() {
        when(autoReplyMapper.selectById(200L)).thenReturn(config(1, "谢谢关注"));
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        when(stringRedisTemplate.execute(any(), anyList(), any())).thenReturn(61L);

        service.sendFollowAutoReply(9L, 100L, 200L);

        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    @DisplayName("正常投递：消息带来源标识、未读递增、会话预览带自动回复前缀")
    void testSendSuccess() {
        when(autoReplyMapper.selectById(200L)).thenReturn(config(1, "谢谢关注"));
        when(imMessageMapper.selectBySource("FOLLOW_AUTO_REPLY", 9L)).thenReturn(null);
        when(userClient.isBlocked(100L, 200L)).thenReturn(ResponseResult.okResult(Map.of("blocked", false)));
        stubRedisNormal();

        ImSession session = new ImSession();
        session.setId(55L);
        when(imSessionMapper.selectBySessionKey(anyString())).thenReturn(session);

        service.sendFollowAutoReply(9L, 100L, 200L);

        ArgumentCaptor<ImMessage> captor = ArgumentCaptor.forClass(ImMessage.class);
        verify(imMessageMapper).insert(captor.capture());
        ImMessage message = captor.getValue();
        assertEquals(55L, message.getSessionId());
        assertEquals(200L, message.getSenderId());
        assertEquals(100L, message.getReceiverId());
        assertEquals("谢谢关注", message.getContent());
        assertEquals(2, message.getSenderType());
        assertEquals("FOLLOW_AUTO_REPLY", message.getSourceType());
        assertEquals(9L, message.getSourceId());
        assertEquals(0, message.getStatus());

        verify(imSessionMapper).applyIncomingMessage(eq(55L), eq(100L),
            contains("[自动回复]"), any(LocalDateTime.class));
        // 限流计数两次 + 冷却标记一次，都走 Lua 脚本
        verify(stringRedisTemplate, times(3)).execute(any(), anyList(), any());
    }

    @Test
    @DisplayName("双方拉黑时不发送")
    void testSendBlocked() {
        when(autoReplyMapper.selectById(200L)).thenReturn(config(1, "谢谢关注"));
        when(imMessageMapper.selectBySource(anyString(), anyLong())).thenReturn(null);
        when(userClient.isBlocked(100L, 200L)).thenReturn(ResponseResult.okResult(Map.of("blocked", true)));

        service.sendFollowAutoReply(9L, 100L, 200L);

        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }

    @Test
    @DisplayName("关注自己不发（兜底）")
    void testSendSelf() {
        service.sendFollowAutoReply(9L, 100L, 100L);

        verify(imMessageMapper, never()).selectBySource(anyString(), anyLong());
        verify(imMessageMapper, never()).insert(any(ImMessage.class));
    }
}
