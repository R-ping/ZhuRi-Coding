package com.heima.content.service.pins.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PinsServiceImpl 单元测试（沸点创作侧：我的沸点列表/统计/发布/删除）
 *
 * 该类继承 ServiceImpl，baseMapper 需通过反射写入私有字段；notificationClient 由 @InjectMocks 注入，
 * 登录用户通过 AppThreadLocalUtil.setUser 控制。
 * 覆盖：
 * - list：未登录拦截、默认取当前用户、按状态过滤(含非法状态)、作者ID参数；
 * - statistics：未登录拦截、总数/已发布/审核中/已拒绝四项计数；
 * - createPins：未登录、内容为空、发布成功(字段初始化)、通知 best-effort(成功/非200/异常/客户端为空)；
 * - deletePins：未登录、id为空、不存在、越权被篡改按不存在处理、成功软删。
 */
class PinsServiceImplTest {

    @Mock
    private ApPinsMapper baseMapper;
    @Mock
    private INotificationClient notificationClient;

    @InjectMocks
    private PinsServiceImpl pinsService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // ServiceImpl 私有 baseMapper 由反射注入，跨 MP 版本稳定
        injectBaseMapper(pinsService, baseMapper);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPins.class);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    // ---------- 辅助 ----------

    /** 通过反射写入 ServiceImpl 继承来的私有字段 baseMapper。 */
    private void injectBaseMapper(Object service, Object mapper) {
        // 3.5.12 起 baseMapper 上移至父类 CrudRepository，ReflectionTestUtils 沿继承链查找
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    /** 直接改 PinsServiceImpl 自身的字段（用于覆盖 notificationClient 为空的场景）。 */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("无法写入字段 " + fieldName, e);
        }
    }

    private ApUser user(Integer id) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname("小明");
        u.setImage("avatar.png");
        return u;
    }

    private void stubPage(Page<ApPins> page) {
        when(baseMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseResult r) {
        return (Map<String, Object>) r.getData();
    }

    // ==================== list ====================

    @Test
    @DisplayName("list - 未登录需要登录")
    void testListNeedLogin() {
        ResponseResult r = pinsService.list(null, 1, 10, null);
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
        verify(baseMapper, never()).selectPage(any(IPage.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("list - 按作者ID参数分页，无状态过滤")
    void testListByAuthorId() {
        AppThreadLocalUtil.setUser(user(1));
        Page<ApPins> page = new Page<>(1, 10);
        ApPins p = new ApPins();
        p.setId(1L);
        page.setRecords(java.util.Collections.singletonList(p));
        page.setTotal(1);
        stubPage(page);

        ResponseResult r = pinsService.list(99L, 1, 10, null);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        assertEquals(1, ((java.util.List<?>) dataOf(r).get("list")).size());
        assertEquals(1L, ((Number) dataOf(r).get("total")).longValue());
    }

    @Test
    @DisplayName("list - 未传作者ID时默认取当前用户，并按状态过滤")
    void testListDefaultUserAndStatus() {
        AppThreadLocalUtil.setUser(user(1));
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(new java.util.ArrayList<>());
        page.setTotal(0);
        stubPage(page);

        ResponseResult reviewing = pinsService.list(null, 1, 10, "reviewing");
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), reviewing.getCode());
    }

    @Test
    @DisplayName("list - 非法状态不设过滤条件仍可查询")
    void testListInvalidStatus() {
        AppThreadLocalUtil.setUser(user(1));
        Page<ApPins> page = new Page<>(1, 10);
        page.setRecords(new java.util.ArrayList<>());
        page.setTotal(0);
        stubPage(page);

        ResponseResult r = pinsService.list(null, 1, 10, "whatever");
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
    }

    // ==================== statistics ====================

    @Test
    @DisplayName("statistics - 未登录需要登录")
    void testStatisticsNeedLogin() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
                pinsService.statistics(null).getCode());
    }

    @Test
    @DisplayName("statistics - 四项计数按调用顺序返回")
    void testStatisticsCounts() {
        AppThreadLocalUtil.setUser(user(1));
        // 顺序：total, published, reviewing, rejected
        when(baseMapper.selectCount(any(Wrapper.class)))
                .thenReturn(10L, 6L, 3L, 1L);

        ResponseResult r = pinsService.statistics(99L);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        Map<String, Object> d = dataOf(r);
        assertEquals(10L, ((Number) d.get("total")).longValue());
        assertEquals(6L, ((Number) d.get("published")).longValue());
        assertEquals(3L, ((Number) d.get("reviewing")).longValue());
        assertEquals(1L, ((Number) d.get("rejected")).longValue());
    }

    // ==================== createPins ====================

    @Test
    @DisplayName("createPins - 未登录需要登录")
    void testCreateNeedLogin() {
        ApPins pins = new ApPins();
        pins.setContent("hi");
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
                pinsService.createPins(pins).getCode());
    }

    @Test
    @DisplayName("createPins - 内容为空参数错误")
    void testCreateEmptyContent() {
        AppThreadLocalUtil.setUser(user(1));
        ApPins pins = new ApPins();
        pins.setContent("");
        ResponseResult r = pinsService.createPins(pins);
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), r.getCode());
        verify(baseMapper, never()).insert((ApPins) any());
    }

    @Test
    @DisplayName("createPins - 发布成功并在新用户字段+初始状态，通知发送成功")
    void testCreateSuccessWithNotificationOk() {
        AppThreadLocalUtil.setUser(user(1));
        when(baseMapper.insert(any(ApPins.class))).thenReturn(1);
        when(notificationClient.createNotification(any())).thenReturn(ResponseResult.okResult());

        ApPins pins = new ApPins();
        pins.setContent("我的第一条沸点");
        ResponseResult r = pinsService.createPins(pins);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
        ApPins saved = (ApPins) r.getData();
        assertEquals(1L, saved.getAuthorId());
        assertEquals("小明", saved.getAuthorName());
        assertEquals("avatar.png", saved.getAuthorImage());
        assertEquals(ApPins.Status.SUBMIT.getCode(), saved.getStatus().byteValue());
        assertFalse(saved.getIsDeleted());
        verify(baseMapper).insert((ApPins) any(ApPins.class));
        verify(notificationClient).createNotification(any());
    }

    @Test
    @DisplayName("createPins - 通知返回非200仅告警")
    void testCreateNotificationNonOk() {
        AppThreadLocalUtil.setUser(user(1));
        when(baseMapper.insert(any(ApPins.class))).thenReturn(1);
        when(notificationClient.createNotification(any()))
                .thenReturn(ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR));
        ApPins pins = new ApPins();
        pins.setContent("hi");
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), pinsService.createPins(pins).getCode());
    }

    @Test
    @DisplayName("createPins - 通知抛异常被吞不影响发布")
    void testCreateNotificationException() {
        AppThreadLocalUtil.setUser(user(1));
        when(baseMapper.insert(any(ApPins.class))).thenReturn(1);
        when(notificationClient.createNotification(any()))
                .thenThrow(new RuntimeException("feign down"));
        ApPins pins = new ApPins();
        pins.setContent("hi");
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), pinsService.createPins(pins).getCode());
        verify(baseMapper).insert((ApPins) any(ApPins.class));
    }

    @Test
    @DisplayName("createPins - 通知客户端为空跳过")
    void testCreateNoNotificationClient() throws Exception {
        AppThreadLocalUtil.setUser(user(1));
        when(baseMapper.insert(any(ApPins.class))).thenReturn(1);
        setField(pinsService, "notificationClient", null);

        ApPins pins = new ApPins();
        pins.setContent("hi");
        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), pinsService.createPins(pins).getCode());
        verify(baseMapper).insert((ApPins) any(ApPins.class));
    }

    // ==================== deletePins ====================

    @Test
    @DisplayName("deletePins - 未登录/id为空")
    void testDeleteGuards() {
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), pinsService.deletePins(1L).getCode());

        AppThreadLocalUtil.setUser(user(1));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), pinsService.deletePins(null).getCode());
    }

    @Test
    @DisplayName("deletePins - 不存在或越权按不存在处理")
    void testDeleteNotExistOrNotOwner() {
        AppThreadLocalUtil.setUser(user(1));
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), pinsService.deletePins(1L).getCode());

        ApPins other = new ApPins();
        other.setId(2L);
        other.setAuthorId(999L);
        when(baseMapper.selectById(2L)).thenReturn(other);
        assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), pinsService.deletePins(2L).getCode());
        verify(baseMapper, never()).updateById((ApPins) any());
    }

    @Test
    @DisplayName("deletePins - 本人软删成功")
    void testDeleteSuccess() {
        AppThreadLocalUtil.setUser(user(1));
        ApPins mine = new ApPins();
        mine.setId(3L);
        mine.setAuthorId(1L);
        mine.setIsDeleted(false);
        when(baseMapper.selectById(3L)).thenReturn(mine);
        when(baseMapper.updateById(any(ApPins.class))).thenReturn(1);

        assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), pinsService.deletePins(3L).getCode());
        assertTrue(mine.getIsDeleted());
        verify(baseMapper).updateById((ApPins) any(ApPins.class));
    }
}