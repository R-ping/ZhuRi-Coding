package com.zhuri.coding.content.service.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.ops.ApBannerMapper;
import com.zhuri.coding.content.mapper.ops.ApPopupMapper;
import com.zhuri.coding.content.service.ops.impl.OpsBannerServiceImpl;
import com.zhuri.coding.model.ops.pojos.ApBanner;
import com.zhuri.coding.model.ops.pojos.ApPopup;
import com.zhuri.coding.model.ops.vos.BannerVO;
import com.zhuri.coding.model.ops.vos.CurrentPopupVO;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * C 端运营位读接口单测。
 *
 * <p>盯住"首页挂错东西 / 重复骚扰"的几类失效：
 * <ol>
 *   <li><b>Banner 只出启用中且时间窗内的</b>：时间窗判定在 SQL 里做
 *       （NULL 视为不限），到点自动上下线不依赖任何定时任务；</li>
 *   <li><b>当前弹窗跳过已关闭、新盖旧</b>：用户关过的弹窗不能再弹；
 *       多条生效中时只交出最新的未关闭一条；</li>
 *   <li><b>未登录安静返回空</b>：弹窗是可选信息，不为它打断未登录用户；</li>
 *   <li><b>关闭弹窗不静默成功</b>：弹窗已删/不存在要如实返回 false。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("C 端运营位（OpsBannerServiceImpl）")
class OpsBannerServiceImplTest {

    private static final long USER_ID = 77L;

    @Mock
    private ApBannerMapper apBannerMapper;

    @Mock
    private ApPopupMapper apPopupMapper;

    @Mock
    private PopupCloseStore popupCloseStore;

    @InjectMocks
    private OpsBannerServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApBanner.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApPopup.class);
    }

    // ==================== 造数 ====================

    private static ApBanner banner(long id, int sortOrder) {
        ApBanner b = new ApBanner();
        b.setId(id);
        b.setTitle("运营备注：" + id);
        b.setImageUrl("https://cdn.zhuri.test/banner-" + id + ".png");
        b.setLinkUrl("/topic/" + id);
        b.setSortOrder(sortOrder);
        b.setStatus(ApBanner.STATUS_ENABLED);
        return b;
    }

    private static ApPopup popup(long id) {
        ApPopup p = new ApPopup();
        p.setId(id);
        p.setTitle("双旦活动公告");
        p.setContent("活动期间创作激励翻倍");
        p.setImageUrl("https://cdn.zhuri.test/popup.png");
        p.setButtonText("去看看");
        p.setLinkUrl("/activity/1");
        p.setStatus(ApPopup.STATUS_ENABLED);
        p.setStartTime(new Date(System.currentTimeMillis() - 60_000L));
        p.setEndTime(new Date(System.currentTimeMillis() + 86_400_000L));
        return p;
    }

    // ==================== Banner 列表 ====================

    @Nested
    @DisplayName("首页轮播")
    class Banners {

        @Test
        @DisplayName("可见性判定在 SQL：status=1 且开始已到（NULL 不限）且结束未到（NULL 不限）")
        @SuppressWarnings("unchecked")
        void filtersVisibilityInSql() {
            AtomicRefHolder holder = new AtomicRefHolder();
            when(apBannerMapper.selectList(any())).thenAnswer(inv -> {
                holder.wrapper.set(inv.getArgument(0));
                return List.of(banner(2L, 0), banner(1L, 0));
            });

            List<BannerVO> result = service.listBanners();

            LambdaQueryWrapper<ApBanner> wrapper =
                (LambdaQueryWrapper<ApBanner>) holder.wrapper.get();
            String sql = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApBanner.STATUS_ENABLED),
                "只出启用中的 Banner：" + params);
            assertTrue(sql.contains("start_time"), "开始时间条件必须进 SQL：" + sql);
            assertTrue(sql.contains("end_time"), "结束时间条件必须进 SQL：" + sql);
            assertTrue(sql.toUpperCase().contains("IS NULL"),
                "时间窗可空，NULL 视为不限：" + sql);
            assertTrue(sql.contains("sort_order ASC"), "展示顺序升序：" + sql);
            assertTrue(sql.contains("id DESC"), "同序号 id 兜底：" + sql);

            assertEquals(2, result.size());
            assertEquals(2L, result.get(0).getId());
            assertEquals("https://cdn.zhuri.test/banner-2.png", result.get(0).getImageUrl());
            assertEquals("/topic/2", result.get(0).getLinkUrl());
        }

        /** 泛型引用容器（selectList 的 wrapper 捕获） */
        private static final class AtomicRefHolder {
            private final java.util.concurrent.atomic.AtomicReference<Wrapper<?>> wrapper =
                new java.util.concurrent.atomic.AtomicReference<>();
        }
    }

    // ==================== 当前弹窗 ====================

    @Nested
    @DisplayName("当前弹窗")
    class CurrentPopup {

        @Test
        @DisplayName("未登录 → 安静返回 null：关闭记录没有身份可落，且不该为弹窗打断未登录用户")
        void anonymousGetsNothing() {
            assertNull(service.currentPopup(null));
            verifyNoInteractions(apPopupMapper, popupCloseStore);
        }

        @Test
        @DisplayName("候选查询：生效中（窗口 NOT NULL 直接 le/gt）、id 降序、限 10 条")
        @SuppressWarnings("unchecked")
        void queriesActiveCandidatesNewestFirst() {
            when(apPopupMapper.selectList(any())).thenReturn(List.of());

            assertNull(service.currentPopup(USER_ID));

            ArgumentCaptor<Wrapper<ApPopup>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(apPopupMapper).selectList(captor.capture());
            LambdaQueryWrapper<ApPopup> wrapper = (LambdaQueryWrapper<ApPopup>) captor.getValue();
            String sql = wrapper.getSqlSegment();
            Map<String, Object> params = wrapper.getParamNameValuePairs();
            assertTrue(params.containsValue(ApPopup.STATUS_ENABLED), "只出启用中的弹窗：" + params);
            assertTrue(sql.contains("start_time") && sql.contains("end_time"),
                "时间窗条件必须进 SQL：" + sql);
            assertTrue(sql.contains("id DESC"), "新弹窗盖旧弹窗：" + sql);
            assertTrue(sql.toUpperCase().contains("LIMIT 10"), "候选收敛上限：" + sql);
        }

        @Test
        @DisplayName("最新一条已关闭 → 跳到下一条未关闭的（新公告也要给没关过的人看）")
        void skipsClosedPopups() {
            ApPopup newest = popup(30L);
            ApPopup older = popup(20L);
            when(apPopupMapper.selectList(any())).thenReturn(List.of(newest, older));
            when(popupCloseStore.isClosed(30L, USER_ID)).thenReturn(true);
            when(popupCloseStore.isClosed(20L, USER_ID)).thenReturn(false);

            CurrentPopupVO vo = service.currentPopup(USER_ID);

            assertNotNull(vo);
            assertEquals(20L, vo.getId(), "最新一条被关过，回退到旧弹窗而不是沉默");
            assertEquals("双旦活动公告", vo.getTitle());
            assertEquals("去看看", vo.getButtonText());
            assertEquals("/activity/1", vo.getLinkUrl());
            assertEquals(older.getEndTime(), vo.getEndTime());
        }

        @Test
        @DisplayName("全部已关闭 → null（不硬塞一条）")
        void allClosedReturnsNothing() {
            when(apPopupMapper.selectList(any())).thenReturn(List.of(popup(30L)));
            when(popupCloseStore.isClosed(30L, USER_ID)).thenReturn(true);

            assertNull(service.currentPopup(USER_ID));
        }
    }

    // ==================== 关闭弹窗 ====================

    @Nested
    @DisplayName("关闭弹窗")
    class ClosePopup {

        @Test
        @DisplayName("参数缺失 / 非法 → false，不查库、不写关闭记录")
        void rejectsInvalidInput() {
            assertFalse(service.closePopup(null, 1L), "未登录没有身份可记");
            assertFalse(service.closePopup(USER_ID, null));
            assertFalse(service.closePopup(USER_ID, 0L));

            verifyNoInteractions(apPopupMapper, popupCloseStore);
        }

        @Test
        @DisplayName("弹窗不存在（已删除）→ false：告诉调用方'没有这回事'，而不是当成功掩盖")
        void missingPopupReturnsFalse() {
            when(apPopupMapper.selectById(30L)).thenReturn(null);

            assertFalse(service.closePopup(USER_ID, 30L));
            verify(popupCloseStore, never()).markClosed(any(), any(), any());
        }

        @Test
        @DisplayName("弹窗存在 → 记录关闭（TTL 取该弹窗结束时间）并返回 true")
        void marksClosedWithPopupEndTime() {
            ApPopup popup = popup(30L);
            when(apPopupMapper.selectById(30L)).thenReturn(popup);

            assertTrue(service.closePopup(USER_ID, 30L));
            verify(popupCloseStore).markClosed(30L, USER_ID, popup.getEndTime());
        }
    }
}
