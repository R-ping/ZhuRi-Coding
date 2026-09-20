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
import com.zhuri.coding.notification.service.ImService;
import com.zhuri.coding.notification.service.ImStateMachine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class ImServiceImpl implements ImService {

    @Autowired
    private ImSessionMapper imSessionMapper;

    @Autowired
    private ImMessageMapper imMessageMapper;

    @Autowired
    private ImStateMachine imStateMachine;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private IUserClient userClient;

    @Autowired(required = false)
    private IFollowClient followClient;

    @Override
    public ResponseResult listSessions(Long userId) {
        List<ImSession> sessions = imSessionMapper.selectByUserId(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ImSession s : sessions) {
            Long peerId = s.getUser1Id().equals(userId) ? s.getUser2Id() : s.getUser1Id();
            Map<String, Object> item = new HashMap<>();
            item.put("session_id", s.getId());
            item.put("session_key", s.getSessionKey());
            item.put("peer_id", peerId);
            // 补充对方昵称与头像，供会话列表展示
            Map<String, String> peer = resolvePeerInfo(peerId);
            item.put("peer_name", peer.get("name"));
            item.put("peer_avatar", peer.get("avatar"));
            item.put("last_message", s.getLastMessage());
            item.put("last_message_at", s.getLastMessageAt() != null ? s.getLastMessageAt().toString() : null);
            int unread = s.getUser1Id().equals(userId)
                    ? (s.getUser1UnreadCount() != null ? s.getUser1UnreadCount() : 0)
                    : (s.getUser2UnreadCount() != null ? s.getUser2UnreadCount() : 0);
            item.put("unread_count", unread);
            boolean isActive = s.getIsActive() == 1;
            item.put("is_active", isActive);
            // 当前用户是否能无限发送：对方已回复 或 对方关注了当前用户
            item.put("can_send_unlimited", isActive || peerFollowsViewer(peerId, userId));
            list.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult getOrCreateSession(Long userId, Long peerId) {
        if (peerId == null || peerId.equals(userId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "目标用户无效");
        }

        String sessionKey = buildSessionKey(userId, peerId);
        ImSession session = getOrInsertSession(sessionKey, userId, peerId);

        Map<String, Object> result = new HashMap<>();
        result.put("session_id", session.getId());
        result.put("peer_id", peerId);
        Map<String, String> peer = resolvePeerInfo(peerId);
        result.put("peer_name", peer.get("name"));
        result.put("peer_avatar", peer.get("avatar"));
        result.put("last_message", session.getLastMessage());
        result.put("last_message_at", session.getLastMessageAt() != null ? session.getLastMessageAt().toString() : null);
        int unread = session.getUser1Id().equals(userId)
                ? (session.getUser1UnreadCount() != null ? session.getUser1UnreadCount() : 0)
                : (session.getUser2UnreadCount() != null ? session.getUser2UnreadCount() : 0);
        result.put("unread_count", unread);
        boolean isActive = session.getIsActive() == 1;
        result.put("is_active", isActive);
        // 当前用户是否能无限发送：对方已回复 或 对方关注了当前用户
        result.put("can_send_unlimited", isActive || peerFollowsViewer(peerId, userId));
        return ResponseResult.okResult(result);
    }

    /**
     * 判断对方(peerId)是否关注了当前用户(viewerId)；关注服务不可用时返回 false
     */
    private boolean peerFollowsViewer(Long peerId, Long viewerId) {
        if (peerId == null || viewerId == null || followClient == null) {
            return false;
        }
        try {
            ResponseResult res = followClient.isFollowing(peerId, viewerId);
            if (res != null && res.getCode() == 200 && res.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) res.getData();
                Object v = data.get("isFollowing");
                return v != null && Boolean.parseBoolean(String.valueOf(v));
            }
        } catch (Exception e) {
            log.warn("查询对方关注关系失败, peerId={}, viewerId={}", peerId, viewerId, e);
        }
        return false;
    }

    /**
     * 通过 Feign 调用用户服务解析对方昵称/头像，失败时返回空字符串兜底
     */
    private Map<String, String> resolvePeerInfo(Long peerId) {
        Map<String, String> info = new HashMap<>();
        info.put("name", "");
        info.put("avatar", "");
        if (peerId == null || userClient == null) {
            return info;
        }
        try {
            ResponseResult res = userClient.getPublicInfo(peerId);
            if (res != null && res.getCode() == 200 && res.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) res.getData();
                info.put("name", data.get("nickname") != null ? String.valueOf(data.get("nickname")) : "");
                info.put("avatar", data.get("avatar") != null ? String.valueOf(data.get("avatar")) : "");
            }
        } catch (Exception e) {
            log.warn("解析用户信息失败, peerId={}", peerId, e);
        }
        return info;
    }

    @Override
    public ResponseResult listMessages(Long userId, Long sessionId, Long cursor, Integer size) {
        if (sessionId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (getPeerUserId(sessionId, userId) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权访问该会话");
        }
        int limit = (size == null || size <= 0) ? 20 : Math.min(size, 50);
        List<ImMessage> messages = imMessageMapper.selectBySessionId(sessionId, cursor, limit);
        Collections.reverse(messages);

        List<Map<String, Object>> list = new ArrayList<>();
        for (ImMessage m : messages) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", m.getId());
            item.put("session_id", m.getSessionId());
            item.put("sender_id", m.getSenderId());
            item.put("receiver_id", m.getReceiverId());
            item.put("content", m.getContent());
            item.put("msg_type", m.getMsgType());
            item.put("status", m.getStatus());
            item.put("is_self", m.getSenderId().equals(userId));
            item.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            list.add(item);
        }

        boolean hasMore = !messages.isEmpty() && messages.size() == limit;
        Long nextCursor = hasMore ? messages.get(0).getId() : null;

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("next_cursor", nextCursor);
        result.put("has_more", hasMore);
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult sendMessage(Long senderId, ImMessageDto dto) {
        Long receiverId = dto.getReceiverId();
        if (receiverId == null || receiverId.equals(senderId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (dto.getContent().trim().length() > 2000) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "消息内容过长（最多2000字）");
        }

        String sessionKey = buildSessionKey(senderId, receiverId);

        ImSession session = getOrInsertSession(sessionKey, senderId, receiverId);

        ImStateMachine.SendPermission permission = imStateMachine.checkPermission(senderId, receiverId, session);
        if (permission == ImStateMachine.SendPermission.LIMIT_REACHED) {
            Map<String, Object> error = new HashMap<>();
            error.put("code", "LIMIT_REACHED");
            error.put("message", "由于对方并未关注你，在收到对方回复之前，你最多只能发送1条文字消息");
            return ResponseResult.errorResult(403, "由于对方并未关注你，在收到对方回复之前，你最多只能发送1条文字消息");
        }

        ImMessage message = new ImMessage();
        message.setSessionId(session.getId());
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setContent(dto.getContent().trim());
        message.setMsgType(dto.getMsgType() != null ? dto.getMsgType() : 1);
        message.setStatus(0);
        message.setIsDeletedForSender(0);
        message.setIsDeletedForReceiver(0);
        message.setCreatedAt(LocalDateTime.now());
        imMessageMapper.insert(message);

        session.setLastMessage(dto.getContent().trim().length() > 50
                ? dto.getContent().trim().substring(0, 50) + "..."
                : dto.getContent().trim());
        session.setLastMessageAt(LocalDateTime.now());
        if (session.getUser1Id().equals(receiverId)) {
            session.setUser1UnreadCount((session.getUser1UnreadCount() != null ? session.getUser1UnreadCount() : 0) + 1);
        } else {
            session.setUser2UnreadCount((session.getUser2UnreadCount() != null ? session.getUser2UnreadCount() : 0) + 1);
        }
        imSessionMapper.updateById(session);

        Map<String, Object> result = new HashMap<>();
        result.put("message_id", message.getId());
        result.put("status", "sent");
        result.put("created_at", message.getCreatedAt().toString());
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult markRead(Long userId, ImReadDto dto) {
        if (dto.getSessionId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (getPeerUserId(dto.getSessionId(), userId) == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "无权操作该会话");
        }
        Long lastReadId = dto.getLastReadId() != null ? dto.getLastReadId() : Long.MAX_VALUE;
        imMessageMapper.markRead(dto.getSessionId(), lastReadId, userId);

        ImSession session = imSessionMapper.selectById(dto.getSessionId());
        if (session != null) {
            if (session.getUser1Id().equals(userId)) {
                session.setUser1UnreadCount(0);
            } else {
                session.setUser2UnreadCount(0);
            }
            if (session.getIsActive() == null || session.getIsActive() == 0) {
                session.setIsActive(1);
            }
            imSessionMapper.updateById(session);
        }

        return ResponseResult.okResult(null);
    }

    private String buildSessionKey(Long uid1, Long uid2) {
        return Math.min(uid1, uid2) + "_" + Math.max(uid1, uid2);
    }

    @Override
    public Long getPeerUserId(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        ImSession session = imSessionMapper.selectById(sessionId);
        if (session == null) {
            return null;
        }
        boolean isMember = session.getUser1Id().equals(userId) || session.getUser2Id().equals(userId);
        if (!isMember) {
            return null;
        }
        return session.getUser1Id().equals(userId) ? session.getUser2Id() : session.getUser1Id();
    }

    /**
     * 查询（不存在则创建）会话。
     * <p>利用 im_sessions.session_key 唯一索引 + 捕获 DuplicateKeyException，在并发调用下避免插入重复会话，
     * 冲突时回读既有会话返回。
     */
    private ImSession getOrInsertSession(String sessionKey, Long uid1, Long uid2) {
        ImSession session = imSessionMapper.selectBySessionKey(sessionKey);
        if (session != null) {
            return session;
        }
        session = new ImSession();
        session.setSessionKey(sessionKey);
        session.setUser1Id(Math.min(uid1, uid2));
        session.setUser2Id(Math.max(uid1, uid2));
        session.setIsActive(0);
        session.setUser1UnreadCount(0);
        session.setUser2UnreadCount(0);
        session.setCreatedAt(LocalDateTime.now());
        try {
            imSessionMapper.insert(session);
            return session;
        } catch (DuplicateKeyException e) {
            // 并发下 session_key 已被其他请求创建，回读既有会话
            ImSession existing = imSessionMapper.selectBySessionKey(sessionKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }
}