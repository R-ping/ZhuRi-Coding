package com.zhuri.coding.content.service.pins.impl;

import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.behavior.service.BehaviorEventBus;
import com.zhuri.coding.content.mapper.pins.ApPinsCommentMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsLikeMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.pins.dtos.PinsCommentDTO;
import com.zhuri.coding.model.pins.dtos.PinsShareDTO;
import com.zhuri.coding.model.pins.pojos.ApPins;
import com.zhuri.coding.model.pins.pojos.ApPinsComment;
import com.zhuri.coding.model.pins.pojos.ApPinsLike;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PinsInteractionService 单元测试（沸点互动：点赞/取消点赞/评论/分享）
 *
 * @Component 依赖各 mapper 与 BehaviorEventBus，均 @Mock 注入。
 * 覆盖：
 * - like：未登录/参数校验、重复点赞幂等、跨用户点赞触发行为事件、本人点赞不触发、事件异常降级、pins 缺失；
 * - unlike：未登录/参数校验、未点赞幂等、正常取消与点赞数下限；
 * - createComment：未登录/参数校验/字数限制、纯文本与纯图评论、回复递增父级回复数、跨用户触发事件与降级；
 * - share：参数校验、pins 不存在、正常分享。
 */
class PinsInteractionServiceTest {

    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApPinsLikeMapper apPinsLikeMapper;
    @Mock
    private ApPinsCommentMapper apPinsCommentMapper;
    @Mock
    private BehaviorEventBus behaviorEventBus;
    @Mock
    private INotificationClient notificationClient;

    @InjectMocks
    private PinsInteractionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void login(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname("评论者");
        u.setImage("avatar.png");
        AppThreadLocalUtil.setUser(u);
    }

    private ApPins pins(Long authorId, Integer likes, Integer comments) {
        ApPins p = new ApPins();
        p.setId(100L);
        p.setAuthorId(authorId);
        p.setLikes(likes);
        p.setComment(comments);
        return p;
    }

    // ---------- like ----------
    @Test
    @DisplayName("like 未登录或参数缺失返回错误")
    void likeGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), service.like(1L).getCode());
        login(7);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.like(null).getCode());
    }

    @Test
    @DisplayName("like 已点赞幂等返回成功")
    void likeDuplicate() {
        login(7);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(new ApPinsLike());
        assertEquals(200, service.like(100L).getCode());
        verify(apPinsLikeMapper, never()).insert(any(ApPinsLike.class));
    }

    @Test
    @DisplayName("like 跨用户点赞触发行为事件")
    void likeCrossUserEvent() {
        login(7);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(null);
        when(apPinsLikeMapper.insert(any(ApPinsLike.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 0));

        assertEquals(200, service.like(100L).getCode());
        verify(apPinsLikeMapper).insert(any(ApPinsLike.class));
        verify(apPinsMapper).updateById(any(ApPins.class));
        verify(behaviorEventBus).execute(any());
    }

    @Test
    @DisplayName("like 点赞自己的沸点不触发行为事件")
    void likeOwnPins() {
        login(7);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(null);
        when(apPinsLikeMapper.insert(any(ApPinsLike.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(7L, null, 0));

        assertEquals(200, service.like(100L).getCode());
        verify(behaviorEventBus, never()).execute(any());
    }

    @Test
    @DisplayName("like 行为事件异常被捕获，接口仍成功")
    void likeEventException() {
        login(7);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(null);
        when(apPinsLikeMapper.insert(any(ApPinsLike.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 0));
        doThrow(new RuntimeException("bus down")).when(behaviorEventBus).execute(any());

        assertEquals(200, service.like(100L).getCode());
    }

    @Test
    @DisplayName("like 沸点缺失时仍成功但不触发事件")
    void likePinsMissing() {
        login(7);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(null);
        when(apPinsLikeMapper.insert(any(ApPinsLike.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(null);

        assertEquals(200, service.like(100L).getCode());
        verify(behaviorEventBus, never()).execute(any());
    }

    // ---------- unlike ----------
    @Test
    @DisplayName("unlike 未登录/参数缺失/不存在点赞")
    void unlikeGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), service.unlike(1L).getCode());
        login(7);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.unlike(null).getCode());
        when(apPinsLikeMapper.selectOne(any())).thenReturn(null);
        assertEquals(200, service.unlike(100L).getCode());
        verify(apPinsLikeMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("unlike 正常取消并递减点赞数（下限0）")
    void unlikeOk() {
        login(7);
        ApPinsLike like = new ApPinsLike();
        like.setId(5L);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(like);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 2, 0));

        assertEquals(200, service.unlike(100L).getCode());
        verify(apPinsLikeMapper).deleteById(5L);
        verify(apPinsMapper).updateById(any(ApPins.class));
    }

    @Test
    @DisplayName("unlike 点赞数减至0不会为负")
    void unlikeFloorAtZero() {
        login(7);
        ApPinsLike like = new ApPinsLike();
        like.setId(5L);
        when(apPinsLikeMapper.selectOne(any())).thenReturn(like);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 0));

        assertEquals(200, service.unlike(100L).getCode());
        verify(apPinsMapper).updateById(any(ApPins.class));
    }

    // ---------- createComment ----------
    @Test
    @DisplayName("createComment 未登录/参数校验")
    void createCommentGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), service.createComment(comment(100L, "x", null)).getCode());
        login(7);
        PinsCommentDTO noPins = comment(null, "x", null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.createComment(noPins).getCode());
        PinsCommentDTO empty = comment(100L, "  ", null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.createComment(empty).getCode());
    }

    @Test
    @DisplayName("createComment 超过1000字返回错误")
    void createCommentTooLong() {
        login(7);
        PinsCommentDTO tooLong = comment(100L, "a".repeat(1001), null);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.createComment(tooLong).getCode());
    }

    @Test
    @DisplayName("createComment 纯文本评论并触发跨用户事件")
    void createCommentTextOk() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 3));

        ResponseResult r = service.createComment(comment(100L, "写得不错", null));
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        verify(apPinsCommentMapper).insert(any(ApPinsComment.class));
        verify(apPinsMapper).updateById(any(ApPins.class));
        verify(behaviorEventBus).execute(any());
    }

    @Test
    @DisplayName("createComment 纯图评论（表情包）合法，回复递增父级回复数")
    void createCommentImageReply() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 3));
        ApPinsComment parent = new ApPinsComment();
        parent.setReplyCount(1);
        when(apPinsCommentMapper.selectById(1L)).thenReturn(parent);

        PinsCommentDTO dto = comment(100L, "", List.of("http://img.png"));
        dto.setParentId(1L);
        assertEquals(200, service.createComment(dto).getCode());
        verify(apPinsCommentMapper).updateById(any(ApPinsComment.class));
    }

    @Test
    @DisplayName("createComment 评论自己的沸点不触发事件，且沸点缺失时成功")
    void createCommentOwnPinsAndMissing() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        // 本人沸点：不触发事件
        when(apPinsMapper.selectById(100L)).thenReturn(pins(7L, 0, 0));
        assertEquals(200, service.createComment(comment(100L, "顶", null)).getCode());
        verify(behaviorEventBus, never()).execute(any());

        // 沸点缺失：仍成功
        when(apPinsMapper.selectById(100L)).thenReturn(null);
        assertEquals(200, service.createComment(comment(100L, "顶", null)).getCode());
        verify(behaviorEventBus, never()).execute(any());
    }

    @Test
    @DisplayName("createComment 行为事件异常被捕获")
    void createCommentEventException() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 0));
        doThrow(new RuntimeException("down")).when(behaviorEventBus).execute(any());

        assertEquals(200, service.createComment(comment(100L, "顶", null)).getCode());
    }

    @Test
    @DisplayName("createComment 跨用户成功 → 向沸点作者发送评论通知（仅有人评论已可见即通知）")
    void createCommentCrossUserSendsNotification() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 0));

        assertEquals(200, service.createComment(comment(100L, "写得不错", null)).getCode());

        // 沸点作者(9)收到一条"评论通知"
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).createNotification(captor.capture());
        Map<String, Object> params = captor.getValue();
        assertEquals(9L, params.get("userId"));      // 通知对象 = 沸点作者
        assertEquals(1, params.get("type"));          // 1 = 评论通知
        assertEquals("100", params.get("sourceId"));  // 目标沸点
        // 等级分仍走行为事件总线，沸点评论通知与等级分互不影响
        verify(behaviorEventBus).execute(any());
    }

    @Test
    @DisplayName("createComment 评论自己的沸点 → 不发送评论通知、不触发行为事件")
    void createCommentOwnPinsNoNotification() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(7L, 0, 0)); // 作者=本人

        assertEquals(200, service.createComment(comment(100L, "顶", null)).getCode());
        verify(notificationClient, never()).createNotification(any());
        verify(behaviorEventBus, never()).execute(any());
    }

    @Test
    @DisplayName("createComment 沸点缺失/无作者 → 不发送评论通知")
    void createCommentNoAuthorNoNotification() {
        login(7);
        when(apPinsCommentMapper.insert(any(ApPinsComment.class))).thenReturn(1);
        when(apPinsMapper.selectById(100L)).thenReturn(null); // 沸点缺失，无作者

        assertEquals(200, service.createComment(comment(100L, "顶", null)).getCode());
        verify(notificationClient, never()).createNotification(any());
    }

    // ---------- share ----------
    @Test
    @DisplayName("share 参数校验与沸点不存在")
    void shareGuards() {
        PinsShareDTO dto = new PinsShareDTO();
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.share(dto).getCode());
        dto.setPinsId(100L);
        when(apPinsMapper.selectById(100L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), service.share(dto).getCode());
    }

    @Test
    @DisplayName("share 正常分享递增分享数")
    void shareOk() {
        PinsShareDTO dto = new PinsShareDTO();
        dto.setPinsId(100L);
        when(apPinsMapper.selectById(100L)).thenReturn(pins(9L, 0, 0));
        assertEquals(200, service.share(dto).getCode());
        verify(apPinsMapper).incrementShare(100L);
    }

    private PinsCommentDTO comment(Long pinsId, String content, List<String> imageUrls) {
        PinsCommentDTO dto = new PinsCommentDTO();
        dto.setPinsId(pinsId);
        dto.setContent(content);
        dto.setImageUrls(imageUrls);
        return dto;
    }
}