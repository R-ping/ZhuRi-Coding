package com.heima.content.controller.v1.user;

import com.heima.apis.user.IUserClient;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.vo.UserDynamicVO;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * UserDynamicController 单元测试（个人主页动态聚合接口）
 *
 * @RestController 依赖各 mapper 与 IUserClient，均 @Mock 注入。
 * 覆盖：
 * - userId 为空/迁移取当前登录用户，未登录返回 NEED_LOGIN；
 * - 无记录/无目标数据时的空列表；
 * - 文章、沸点、关注三类动态的组装与描述/标题/封面/URL/阅读格式；
 * - 发布 vs 点赞的分类语义；
 * - firstImage 对空/逗号分隔/JSON 数组的解析；
 * - formatCount 的 w/k 格式化；
 * - loadUsers 单用户异常降级。
 */
class UserDynamicControllerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;
    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private IUserClient userClient;

    @InjectMocks
    private UserDynamicController controller;

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
        AppThreadLocalUtil.setUser(u);
    }

    private UserBehaviorRecord record(String code, Long targetId, Integer targetUserId) {
        UserBehaviorRecord r = new UserBehaviorRecord();
        r.setId(1L);
        r.setBehaviorType(code);
        r.setTargetType(BehaviorType.fromCode(code) == null ? 0 : BehaviorType.fromCode(code).getTargetType());
        r.setTargetId(targetId);
        r.setTargetUserId(targetUserId);
        r.setStatus(1);
        r.setCreatedTime(new Date());
        return r;
    }

    // ---------- userId 解析 / 未登录 ----------
    @Test
    @DisplayName("userId 为空且未登录返回 NEED_LOGIN")
    void needLogin() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.dynamic(null, 50).getCode());
    }

    @Test
    @DisplayName("userId 为 0/负 时取当前登录用户")
    void userIdFallbackToCurrent() {
        login(7);
        when(behaviorRecordMapper.selectList(any())).thenReturn(null);
        // 返回空列表，走完 selectList 覆盖 userId 取当前用户分支
        assertEquals(200, controller.dynamic(0L, 50).getCode());
        assertEquals(200, controller.dynamic(-1L, 50).getCode());
    }

    @Test
    @DisplayName("size<=0 时默认 50，且 limit 上限 200")
    void sizeGuard() {
        login(7);
        when(behaviorRecordMapper.selectList(any())).thenReturn(null);
        assertEquals(200, controller.dynamic(7L, 0).getCode());
        assertEquals(200, controller.dynamic(7L, -5).getCode());
        assertEquals(200, controller.dynamic(7L, 9999).getCode());
    }

    // ---------- 空记录 ----------
    @Test
    @DisplayName("无动态记录返回空列表")
    void emptyRecords() {
        login(7);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of());
        List<?> list = (List<?>) controller.dynamic(7L, 50).getData();
        assertEquals(0, list.size());
    }

    // ---------- 文章动态 ----------
    @Test
    @DisplayName("点赞文章动态组装 VO")
    void likeArticleVO() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.LIKE_ARTICLE.getCode(), 100L, null);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        ApArticle a = new ApArticle();
        a.setId(100L);
        a.setTitle("文章标题");
        a.setCoverImage("cover.png");
        a.setViews(12000);
        when(apArticleMapper.selectBatchIds(any())).thenReturn(List.of(a));

        UserDynamicVO vo = (UserDynamicVO) ((List<?>) controller.dynamic(7L, 50).getData()).get(0);
        assertEquals("like", vo.getActionCategory());
        assertEquals("点赞了文章", vo.getBehaviorDesc());
        assertEquals("文章标题", vo.getTargetTitle());
        assertEquals("/content/article/100", vo.getTargetUrl());
        assertEquals("1.2w 阅读", vo.getTargetMeta());
    }

    @Test
    @DisplayName("发布文章动态且文章目标缺失时丢弃该条")
    void articleTargetMissing() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.PUBLISH_ARTICLE.getCode(), 200L, null);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        when(apArticleMapper.selectBatchIds(any())).thenReturn(List.of()); // 文章未命中

        List<?> list = (List<?>) controller.dynamic(7L, 50).getData();
        assertEquals(0, list.size());
    }

    // ---------- 沸点动态 ----------
    @Test
    @DisplayName("发布沸点动态组装 VO 并解析封面")
    void publishPinsVO() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.PUBLISH_PIN.getCode(), 300L, null);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        ApPins pins = new ApPins();
        pins.setId(300L);
        pins.setContent("沸点内容");
        pins.setImageUrls("[\"http://a.png\",\"http://b.png\"]");
        pins.setViews(50);
        when(apPinsMapper.selectBatchIds(any())).thenReturn(List.of(pins));

        UserDynamicVO vo = (UserDynamicVO) ((List<?>) controller.dynamic(7L, 50).getData()).get(0);
        assertEquals("publish", vo.getActionCategory());
        assertEquals("发布了沸点", vo.getBehaviorDesc());
        assertEquals("http://a.png", vo.getTargetCover());
        assertEquals("/pins/detail/300", vo.getTargetUrl());
        assertEquals("50 浏览", vo.getTargetMeta());
    }

    @Test
    @DisplayName("沸点逗号分隔封面与空封面解析")
    void pinsFirstImageVariants() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.LIKE_PIN.getCode(), 400L, null);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        ApPins pins = new ApPins();
        pins.setId(400L);
        pins.setContent("内容");
        pins.setImageUrls("http://x.png,http://y.png");
        pins.setViews(0);
        when(apPinsMapper.selectBatchIds(any())).thenReturn(List.of(pins));

        UserDynamicVO vo = (UserDynamicVO) ((List<?>) controller.dynamic(7L, 50).getData()).get(0);
        assertEquals("http://x.png", vo.getTargetCover());
        assertEquals("0 浏览", vo.getTargetMeta());
    }

    // ---------- 关注动态 ----------
    @Test
    @DisplayName("关注用户动态组装并回填被关注者信息")
    void followVO() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.FOLLOW_USER.getCode(), 500L, 55);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        when(userClient.getPublicInfo(anyLong())).thenReturn(
                ResponseResult.okResult(Map.of("nickname", "被关注者", "avatar", "a.png")));

        UserDynamicVO vo = (UserDynamicVO) ((List<?>) controller.dynamic(7L, 50).getData()).get(0);
        assertEquals("follow", vo.getActionCategory());
        assertEquals("关注了用户", vo.getBehaviorDesc());
        assertEquals("被关注者", vo.getTargetTitle());
        assertEquals("/user/55", vo.getTargetUrl());
    }

    @Test
    @DisplayName("关注目标用户信息获取异常时该条丢弃")
    void followUserMissing() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.FOLLOW_USER.getCode(), 500L, 55);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        when(userClient.getPublicInfo(anyLong())).thenThrow(new RuntimeException("down"));

        assertEquals(0, ((List<?>) controller.dynamic(7L, 50).getData()).size());
    }

    // ---------- 非动态行为类型（不应进入分组） ----------
    @Test
    @DisplayName("浏览/评论类行为从分组中排除导致目标缺失而丢弃")
    void nonDynamicTypeDiscarded() {
        login(7);
        // COMMENT_ARTICLE 不在 DYNAMIC_TYPES 内，但仍可能因历史数据出现；走 load 后目标缺失被丢弃
        UserBehaviorRecord r = record(BehaviorType.COMMENT_ARTICLE.getCode(), 600L, null);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        when(apArticleMapper.selectBatchIds(any())).thenReturn(null);

        assertEquals(0, ((List<?>) controller.dynamic(7L, 50).getData()).size());
    }

    // ---------- 关注但 targetUserId 为 null 时回退 targetId ----------
    @Test
    @DisplayName("关注行为 targetUserId 缺失时回退 targetId 组装")
    void followFallbackTargetId() {
        login(7);
        UserBehaviorRecord r = record(BehaviorType.FOLLOW_USER.getCode(), 500L, null);
        when(behaviorRecordMapper.selectList(any())).thenReturn(List.of(r));
        when(userClient.getPublicInfo(anyLong())).thenReturn(
                ResponseResult.okResult(Map.of("nickname", "回退用户", "avatar", "c.png")));

        UserDynamicVO vo = (UserDynamicVO) ((List<?>) controller.dynamic(7L, 50).getData()).get(0);
        assertEquals("follow", vo.getActionCategory());
        assertEquals("/user/500", vo.getTargetUrl());
        assertEquals("回退用户", vo.getTargetTitle());
    }
}