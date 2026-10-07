package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.content.mapper.topic.TopicMapper;
import com.zhuri.coding.content.service.admin.impl.AdminActivityServiceImpl;
import com.zhuri.coding.model.activity.ActivityStatus;
import com.zhuri.coding.model.activity.ActivityTaxonomy;
import com.zhuri.coding.model.activity.pojos.ApActivity;
import com.zhuri.coding.model.admin.dtos.AdminActivitySaveDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminActivityVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.topic.pojos.ApTopic;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
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
 * 活动管理（CMS）单测。
 *
 * <p>盯住五件事，每一条都对应一种"接口返回成功、库里却不是那个样"的失效：
 * <ol>
 *   <li><b>新建一律落草稿</b>：一步到位上线会让"何时对外可见"没有独立的时间点与理由；</li>
 *   <li><b>幂等闸口</b>：字段没变、状态已经是目标状态时要明确报错，不能静默成功
 *       （静默成功会让运营以为刚改的东西生效了，审计里还多一条没有差别的记录）；</li>
 *   <li><b>清空描述 / 封面 / 关联话题要真的写进库</b>：用 {@code updateById} 做这件事会
 *       静默失效（它跳过 null 字段），所以这里按 {@code getSqlSet()} 断言 SET 子句里
 *       确实有那几列；</li>
 *   <li><b>已发布的活动改日期要重算阶段</b>：延期就是靠这个把"已结束"改回"进行中"；</li>
 *   <li><b>状态类动作靠条件更新挡并发</b>：UPDATE 命中 0 行时给"请刷新后重试"，
 *       而不是当作成功。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活动管理（AdminActivityServiceImpl）")
class AdminActivityServiceImplTest {

    private static final long ACT_ID = 21L;
    private static final long TOPIC_ID = 18L;
    private static final String REASON = "季度征文活动，准备上线";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Mock
    private ApActivityMapper apActivityMapper;

    @Mock
    private TopicMapper topicMapper;

    @Mock
    private AdminAuditRecorder auditRecorder;

    @InjectMocks
    private AdminActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器靠实体元信息解析列名。单测没有 MyBatis 会话，必须先手动初始化 ——
        // 漏了不会报"元信息缺失"，只会让断言莫名其妙地失败（异常被包装器内部吞掉）
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApActivity.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApTopic.class);
    }

    // ==================== 新建 ====================

    @Nested
    @DisplayName("新建")
    class Create {

        @Test
        @DisplayName("新建一律落草稿，统计列显式置 0，审计带新 id 与摘要")
        void landsAsDraft() {
            stubInsertAssignsId();
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 10), day(2026, 10, 20)));

            ResponseResult result = service.create(saveDto("2026 年度技术征文大赛", "2026-10-10", "2026-10-20"));

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());

            ArgumentCaptor<ApActivity> inserted = ArgumentCaptor.forClass(ApActivity.class);
            verify(apActivityMapper).insert(inserted.capture());
            ApActivity saved = inserted.getValue();
            assertEquals(ActivityStatus.DRAFT, saved.getStatus(), "新建必须先落草稿，上线是另一个动作");
            assertEquals("2026 年度技术征文大赛", saved.getTitle());
            assertEquals(ActivityTaxonomy.TYPE_ARTICLE, saved.getActivityType());
            assertEquals(ActivityTaxonomy.CATEGORY_HOT, saved.getCategory());
            assertEquals("2026-10-10", dayText(saved.getStartDate()));
            assertEquals("2026-10-20", dayText(saved.getEndDate()));
            assertNotNull(saved.getCreatedTime());
            assertNotNull(saved.getUpdatedTime());
            assertEquals(0, saved.getTotalParticipants().intValue(), "统计列显式写 0，不依赖 DDL 默认值");
            assertEquals(0L, saved.getTotalReadCount().longValue());

            ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
            verify(auditRecorder).recordSuccess(audit.capture());
            assertEquals(AdminActivityService.ACTION_CREATE, audit.getValue().getAction());
            assertEquals(AdminActivityService.TARGET_ACTIVITY, audit.getValue().getTargetType());
            assertEquals(String.valueOf(ACT_ID), audit.getValue().getTargetId(), "审计要能指到新建出来的那条");
            assertEquals(REASON, audit.getValue().getReason());
            assertTrue(audit.getValue().getDetail().contains("新建活动"), audit.getValue().getDetail());
        }

        @Test
        @DisplayName("字段不合法 → 参数错误，既不写库也不留失败审计（手误不该淹没「谁试过但没成功」）")
        void rejectsBadFields() {
            // 标题为空
            assertInvalid(service.create(saveDto("   ", "2026-10-10", "2026-10-20")), "标题");
            // 标题超长（列宽 200）
            assertInvalid(service.create(saveDto("标".repeat(201), "2026-10-10", "2026-10-20")), "200");
            // 日期格式不对
            assertInvalid(service.create(saveDto("征文", "2026/10/10", "2026-10-20")), "yyyy-MM-dd");
            // 不存在的日期（STRICT 解析要拦住 2 月 31 日，而不是顺延到 3 月）
            assertInvalid(service.create(saveDto("征文", "2026-02-31", "2026-03-10")), "yyyy-MM-dd");
            // 结束早于开始
            assertInvalid(service.create(saveDto("征文", "2026-10-20", "2026-10-10")), "结束日期");

            AdminActivitySaveDto badType = saveDto("征文", "2026-10-10", "2026-10-20");
            badType.setActivityType("article2");
            assertInvalid(service.create(badType), "活动类型");

            AdminActivitySaveDto badCategory = saveDto("征文", "2026-10-10", "2026-10-20");
            badCategory.setCategory("devtool");
            assertInvalid(service.create(badCategory), "活动分类");

            verify(apActivityMapper, never()).insert(any(ApActivity.class));
            verify(auditRecorder, never()).recordSuccess(any());
            verify(auditRecorder, never()).recordFailure(any(), any());
        }

        @Test
        @DisplayName("理由缺失 → 参数错误，连审计条目都不构造（审计表的 reason 是 NOT NULL）")
        void rejectsMissingReason() {
            AdminActivitySaveDto dto = saveDto("征文", "2026-10-10", "2026-10-20");
            dto.setReason("   ");

            ResponseResult result = service.create(dto);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("理由"));
            verify(apActivityMapper, never()).insert(any(ApActivity.class));
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("关联话题不存在 → 参数错误（活动表没有外键，写进去只会变成点进去 404 的话题）")
        void rejectsMissingTopic() {
            when(topicMapper.selectById(TOPIC_ID)).thenReturn(null);
            AdminActivitySaveDto dto = saveDto("征文", "2026-10-10", "2026-10-20");
            dto.setTopicId(TOPIC_ID);

            assertInvalid(service.create(dto), "话题");

            verify(apActivityMapper, never()).insert(any(ApActivity.class));
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("关联了话题 → 出参带出话题名（列表上只显示 topicId 的话运营没法核对）")
        void bringsTopicName() {
            stubInsertAssignsId();
            ApActivity saved = activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 10), day(2026, 10, 20));
            saved.setTopicId(TOPIC_ID);
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(saved);
            // 两次查询各有用途：selectById 是"这个 topicId 存不存在"，selectBatchIds 是"取名字"
            when(topicMapper.selectById(TOPIC_ID)).thenReturn(topic(TOPIC_ID, "后端架构演进"));
            when(topicMapper.selectBatchIds(any())).thenReturn(List.of(topic(TOPIC_ID, "后端架构演进")));

            AdminActivitySaveDto dto = saveDto("征文", "2026-10-10", "2026-10-20");
            dto.setTopicId(TOPIC_ID);

            ResponseResult result = service.create(dto);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode(),
                String.valueOf(result.getMessage()));
            AdminActivityVO vo = vo(result);
            assertEquals(TOPIC_ID, vo.getTopicId());
            assertEquals("后端架构演进", vo.getTopicName());
            assertEquals("草稿", vo.getStatusDesc());
        }

        @Test
        @DisplayName("默认值：不传类型/分类时补成 article / hot（否则列上落下 NULL，C 端按分类筛不出来）")
        void fillsDefaults() {
            stubInsertAssignsId();
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 10), day(2026, 10, 20)));

            AdminActivitySaveDto dto = saveDto("征文", "2026-10-10", "2026-10-20");
            dto.setActivityType(null);
            dto.setCategory("  ");
            service.create(dto);

            ArgumentCaptor<ApActivity> inserted = ArgumentCaptor.forClass(ApActivity.class);
            verify(apActivityMapper).insert(inserted.capture());
            assertEquals("article", inserted.getValue().getActivityType());
            assertEquals("hot", inserted.getValue().getCategory());
        }
    }

    // ==================== 编辑 ====================

    @Nested
    @DisplayName("编辑")
    class Update {

        @Test
        @DisplayName("一个字段都没变 → 明确报错（静默成功会让运营以为刚改的生效了）")
        void rejectsUnchanged() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 10), day(2026, 10, 20)));

            ResponseResult result = service.update(ACT_ID,
                saveDto("征文", "2026-10-10", "2026-10-20"));

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("一致"),
                String.valueOf(result.getMessage()));
            verify(apActivityMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("已发布的活动改日期 → 阶段按新日期重算（延期就是把「已结束」改回「进行中」）")
        void recalculatesPhaseForPublished() {
            ApActivity before = activity(ACT_ID, ActivityStatus.ENDED, day(2026, 1, 1), day(2026, 2, 1));
            // 读两次：第一次读现状（还没发布的东西）、第二次读更新后的结果用于出参。
            // thenReturn(a, b) 按顺序给出两次返回值 —— 写成两条 when 的话后一条会覆盖前一条，
            // 于是"现状"变成延期后的样子，这个用例就什么都不证明了。
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(before,
                activity(ACT_ID, ActivityStatus.UPCOMING, day(2999, 1, 1), day(2999, 2, 1)));
            when(apActivityMapper.update(any(), any())).thenReturn(1);

            ResponseResult result = service.update(ACT_ID,
                saveDto("征文（延期）", "2999-01-01", "2999-02-01"));

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            LambdaUpdateWrapper<?> wrapper = capturedUpdate();
            assertTrue(wrapper.getParamNameValuePairs().containsValue(ActivityStatus.UPCOMING),
                "阶段要按新日期重算：" + wrapper.getParamNameValuePairs());

            ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
            verify(auditRecorder).recordSuccess(audit.capture());
            assertTrue(audit.getValue().getDetail().contains("按新日期重算"),
                audit.getValue().getDetail());
        }

        @Test
        @DisplayName("草稿改日期 → 状态保持草稿（还没对外发布，与时间无关）")
        void keepsDraftPhase() {
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(
                activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 1, 1), day(2026, 2, 1)),
                activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 3, 1), day(2026, 4, 1)));
            when(apActivityMapper.update(any(), any())).thenReturn(1);

            service.update(ACT_ID, saveDto("征文", "2026-03-01", "2026-04-01"));

            LambdaUpdateWrapper<?> wrapper = capturedUpdate();
            assertTrue(wrapper.getParamNameValuePairs().containsValue(ActivityStatus.DRAFT),
                "草稿改日期不该被算成阶段：" + wrapper.getParamNameValuePairs());
        }

        @Test
        @DisplayName("清空描述/封面/关联话题要真的写进 SET 子句（updateById 会跳过 null 字段，静默失效）")
        void clearsNullableFields() {
            ApActivity before = activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 10), day(2026, 10, 20));
            before.setDescription("旧描述");
            before.setCoverImage("http://old/cover.jpg");
            before.setTopicId(TOPIC_ID);
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(before);
            when(apActivityMapper.update(any(), any())).thenReturn(1);

            AdminActivitySaveDto dto = saveDto("征文", "2026-10-10", "2026-10-20");
            dto.setDescription(null);
            dto.setCoverImage("  ");
            dto.setTopicId(null);
            service.update(ACT_ID, dto);

            LambdaUpdateWrapper<?> wrapper = capturedUpdate();
            String set = wrapper.getSqlSet();
            for (String column : new String[]{"title", "description", "cover_image", "activity_type",
                "category", "start_date", "end_date", "topic_id", "status", "updated_time"}) {
                assertTrue(set.contains(column + "="), "SET 子句漏了 " + column + "：" + set);
            }
            assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
                "清空要以「写 null」表达，不能整列不写：" + wrapper.getParamNameValuePairs());
        }

        @Test
        @DisplayName("活动不存在 / id 不合法 → 数据不存在，不写库")
        void rejectsMissingActivity() {
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(null);
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.update(ACT_ID, saveDto("征文", "2026-10-10", "2026-10-20")).getCode().intValue());

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.update(0L, saveDto("征文", "2026-10-10", "2026-10-20")).getCode().intValue());

            verify(apActivityMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("落库抛异常 → 业务失败并记一条失败审计（谁试过但没成功也要能看到）")
        void recordsFailureOnException() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 10), day(2026, 10, 20)));
            when(apActivityMapper.update(any(), any()))
                .thenThrow(new RuntimeException("Data too long for column 'title'"));

            RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.update(ACT_ID, saveDto("征文", "2026-11-01", "2026-12-01")));

            assertEquals("Data too long for column 'title'", thrown.getMessage());
            verify(auditRecorder).recordFailure(any(), eq("Data too long for column 'title'"));
            verify(auditRecorder, never()).recordSuccess(any());
        }
    }

    // ==================== 上线 ====================

    @Nested
    @DisplayName("上线")
    class Publish {

        @Test
        @DisplayName("草稿按日期定阶段：已到开始日期 → 进行中，WHERE 带上读到的旧状态")
        void draftBecomesOngoing() {
            Date today = truncateToToday();
            ApActivity before = activity(ACT_ID, ActivityStatus.DRAFT,
                plusDays(today, -1), plusDays(today, 1));
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(before);
            when(apActivityMapper.update(any(), any())).thenReturn(1);

            ResponseResult result = service.publish(ACT_ID, REASON);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            LambdaUpdateWrapper<?> wrapper = capturedUpdate();
            assertTrue(wrapper.getParamNameValuePairs().containsValue(ActivityStatus.ONGOING),
                "要落到进行中：" + wrapper.getParamNameValuePairs());
            assertTrue(wrapper.getSqlSegment().contains("status"),
                "条件更新必须带 status：" + wrapper.getSqlSegment());
            assertTrue(wrapper.getParamNameValuePairs().containsValue(ActivityStatus.DRAFT),
                "条件里的旧状态就是读到的那个：" + wrapper.getParamNameValuePairs());

            ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
            verify(auditRecorder).recordSuccess(audit.capture());
            assertEquals(AdminActivityService.ACTION_PUBLISH, audit.getValue().getAction());
            assertTrue(audit.getValue().getDetail().contains("草稿 -> 进行中"),
                audit.getValue().getDetail());
        }

        @Test
        @DisplayName("重新上线一条早已过期的活动 → 直接落「已结束」，不先闪一下「即将开始」")
        void offlineExpiredBecomesEnded() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.OFFLINE, day(2026, 1, 1), day(2026, 2, 1)));
            when(apActivityMapper.update(any(), any())).thenReturn(1);

            service.publish(ACT_ID, REASON);

            assertTrue(capturedUpdate().getParamNameValuePairs().containsValue(ActivityStatus.ENDED));
        }

        @Test
        @DisplayName("已经是已发布 → 参数错误（空动作没有可写进审计的内容）")
        void rejectsAlreadyPublished() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.ONGOING, day(2026, 10, 1), day(2026, 10, 30)));

            ResponseResult result = service.publish(ACT_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("无需上线"),
                String.valueOf(result.getMessage()));
            verify(apActivityMapper, never()).update(any(), any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("条件更新命中 0 行（并发上线 / 页面停在旧状态重复提交）→ 要求刷新，不当成成功")
        void detectsConcurrentChange() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 1), day(2026, 10, 30)));
            when(apActivityMapper.update(any(), any())).thenReturn(0);

            ResponseResult result = service.publish(ACT_ID, REASON);

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("刷新"),
                String.valueOf(result.getMessage()));
            verify(auditRecorder, never()).recordSuccess(any());
        }

        @Test
        @DisplayName("理由必填（上线决定全站用户能不能看到它，事后只能靠这句话追溯）")
        void rejectsMissingReason() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.publish(ACT_ID, "  ").getCode().intValue());
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.publish(ACT_ID, null).getCode().intValue());
            verifyNoInteractions(apActivityMapper);
            verifyNoInteractions(auditRecorder);
        }
    }

    // ==================== 下线 / 删除 ====================

    @Nested
    @DisplayName("下线与删除")
    class OfflineAndDelete {

        @Test
        @DisplayName("下线只改可见性，不删数据（参与人数、阅读量要留给统计）")
        void offlineKeepsRow() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.ONGOING, day(2026, 10, 1), day(2026, 10, 30)));
            when(apActivityMapper.update(any(), any())).thenReturn(1);

            ResponseResult result = service.offline(ACT_ID, "活动提前结束");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            assertTrue(capturedUpdate().getParamNameValuePairs().containsValue(ActivityStatus.OFFLINE));
            verify(apActivityMapper, never()).delete(any());
        }

        @Test
        @DisplayName("草稿没有「下线」这个概念 → 参数错误（它本来就不可见）")
        void offlineRejectsDraft() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 1), day(2026, 10, 30)));

            ResponseResult result = service.offline(ACT_ID, "撤下");

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("无需下线"),
                String.valueOf(result.getMessage()));
            verify(apActivityMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("删除只对草稿开放，且带 status 条件防止并发把它变成已发布后仍被删")
        void deleteOnlyDraft() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 1), day(2026, 10, 30)));
            when(apActivityMapper.delete(any())).thenReturn(1);

            ResponseResult result = service.delete(ACT_ID, "建错了，标题重复");

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            ArgumentCaptor<Wrapper<ApActivity>> where = deleteCaptor();
            verify(apActivityMapper).delete(where.capture());
            // 删除的 where 是查询包装器（不是更新包装器）—— 别拿 LambdaUpdateWrapper 去转
            String segment = ((LambdaQueryWrapper<?>) where.getValue()).getSqlSegment();
            assertTrue(segment.contains("status"),
                "删除必须带 status=draft 条件：" + segment);

            ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
            verify(auditRecorder).recordSuccess(audit.capture());
            assertEquals(AdminActivityService.ACTION_DELETE, audit.getValue().getAction());
            assertEquals(String.valueOf(ACT_ID), audit.getValue().getTargetId(),
                "记录要能在被记录的对象消失之后依然读得懂");
        }

        @Test
        @DisplayName("已发布的活动不能删 → 提示改用下线（下架与从历史里抹去是两件事）")
        void deleteRejectsPublished() {
            when(apActivityMapper.selectById(ACT_ID))
                .thenReturn(activity(ACT_ID, ActivityStatus.ENDED, day(2026, 1, 1), day(2026, 2, 1)));

            ResponseResult result = service.delete(ACT_ID, "删掉它");

            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("下线"),
                String.valueOf(result.getMessage()));
            verify(apActivityMapper, never()).delete(any());
            verifyNoInteractions(auditRecorder);
        }

        @Test
        @DisplayName("活动不存在 → 数据不存在，不写库")
        void rejectsMissing() {
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(null);
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.offline(ACT_ID, "撤下").getCode().intValue());
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.delete(ACT_ID, "删掉").getCode().intValue());
            verify(apActivityMapper, never()).update(any(), any());
            verify(apActivityMapper, never()).delete(any());
        }
    }

    // ==================== 读 ====================

    @Nested
    @DisplayName("列表与详情")
    class Read {

        @Test
        @DisplayName("列表默认不过滤状态：草稿与已下线都要看得见（运营看的是库里的全部）")
        void pageIncludesDrafts() {
            Page<ApActivity> dbPage = new Page<>(1, 20);
            dbPage.setRecords(List.of(
                activity(ACT_ID, ActivityStatus.DRAFT, day(2026, 10, 1), day(2026, 10, 30)),
                activity(22L, ActivityStatus.OFFLINE, day(2026, 9, 1), day(2026, 9, 30))));
            dbPage.setTotal(2);
            when(apActivityMapper.selectPage(any(), any())).thenReturn(dbPage);

            ResponseResult result = service.page(null, null, null, null, 1, 20);

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), result.getCode());
            List<AdminActivityVO> list = listOf(result);
            assertEquals(2, list.size());
            assertEquals("草稿", list.get(0).getStatusDesc());
            assertEquals("已下线", list.get(1).getStatusDesc());
            assertEquals(2L, ((Number) ((Map<?, ?>) result.getData()).get("total")).longValue());
        }

        @Test
        @DisplayName("状态/类型/分类拼错 → 参数错误，不返回空列表（空白最容易被误读成「确实没数据」）")
        void pageRejectsUnknownFilters() {
            assertInvalid(service.page(null, "draf", null, null, 1, 20), "活动状态");
            assertInvalid(service.page(null, null, "artilce", null, 1, 20), "活动类型");
            assertInvalid(service.page(null, null, null, "devtool", 1, 20), "活动分类");
            verifyNoInteractions(apActivityMapper);
        }

        @Test
        @DisplayName("列表一次批量取话题名（一页 20 条不该变成 20 次查询）")
        void pageLoadsTopicNamesInBatch() {
            ApActivity withTopic = activity(ACT_ID, ActivityStatus.ONGOING, day(2026, 10, 1), day(2026, 10, 30));
            withTopic.setTopicId(TOPIC_ID);
            Page<ApActivity> dbPage = new Page<>(1, 20);
            dbPage.setRecords(List.of(withTopic));
            dbPage.setTotal(1);
            when(apActivityMapper.selectPage(any(), any())).thenReturn(dbPage);
            when(topicMapper.selectBatchIds(any())).thenReturn(List.of(topic(TOPIC_ID, "云原生")));

            List<AdminActivityVO> list = listOf(service.page(null, "ongoing", null, null, 1, 20));

            assertEquals("云原生", list.get(0).getTopicName());
            assertEquals("进行中", list.get(0).getStatusDesc());
        }

        @Test
        @DisplayName("详情：不存在 → 数据不存在")
        void detailMissing() {
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(null);
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(),
                service.detail(ACT_ID).getCode().intValue());
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.detail(0L).getCode().intValue());
        }

        @Test
        @DisplayName("详情：关联话题已被删除时只带出 topicId，不报错也不隐藏")
        void detailKeepsDanglingTopicId() {
            ApActivity dangling = activity(ACT_ID, ActivityStatus.ONGOING, day(2026, 10, 1), day(2026, 10, 30));
            dangling.setTopicId(TOPIC_ID);
            when(apActivityMapper.selectById(ACT_ID)).thenReturn(dangling);
            // 话题已被删除：批量查不到任何东西
            when(topicMapper.selectBatchIds(any())).thenReturn(List.of());

            AdminActivityVO vo = vo(service.detail(ACT_ID));

            assertEquals(TOPIC_ID, vo.getTopicId());
            assertNull(vo.getTopicName());
        }
    }

    // ==================== 工具 ====================

    private void stubInsertAssignsId() {
        when(apActivityMapper.insert(any(ApActivity.class))).thenAnswer(invocation -> {
            ApActivity entity = invocation.getArgument(0);
            entity.setId(ACT_ID);
            return 1;
        });
    }

    private AdminActivitySaveDto saveDto(String title, String start, String end) {
        AdminActivitySaveDto dto = new AdminActivitySaveDto();
        dto.setTitle(title);
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setActivityType(ActivityTaxonomy.TYPE_ARTICLE);
        dto.setCategory(ActivityTaxonomy.CATEGORY_HOT);
        dto.setReason(REASON);
        return dto;
    }

    private static ApActivity activity(Long id, String status, Date start, Date end) {
        ApActivity activity = new ApActivity();
        activity.setId(id);
        activity.setTitle("征文");
        activity.setActivityType(ActivityTaxonomy.TYPE_ARTICLE);
        activity.setCategory(ActivityTaxonomy.CATEGORY_HOT);
        activity.setStatus(status);
        activity.setStartDate(start);
        activity.setEndDate(end);
        activity.setTotalParticipants(0);
        activity.setTotalReadCount(0L);
        return activity;
    }

    private static ApTopic topic(Long id, String name) {
        ApTopic topic = new ApTopic();
        topic.setId(id);
        topic.setName(name);
        return topic;
    }

    /** 断言"参数错误且提示里带某段文字" */
    private static void assertInvalid(ResponseResult result, String messagePart) {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue(),
            "期望参数错误，实际：" + result.getCode() + " " + result.getMessage());
        assertTrue(String.valueOf(result.getMessage()).contains(messagePart),
            "提示里应包含「" + messagePart + "」，实际：" + result.getMessage());
    }

    /**
     * 取最近一次 UPDATE 的包装器（同时约束"只更新了一次"，SET 与 WHERE 都靠它断言）。
     *
     * <p>⚠️ 返回前先渲染一次 {@code getSqlSegment()}：MyBatis-Plus 的 WHERE 条件是**惰性求值**的
     * （存成 lambda，拼 SQL 片段时才把值放进参数表），而 SET 子句是构造时就写好的。
     * 不渲染就读 {@code getParamNameValuePairs()}，只能看到 SET 的那一半 ——
     * 表现是"条件里的旧状态莫名其妙不见了"。
     */
    private LambdaUpdateWrapper<?> capturedUpdate() {
        ArgumentCaptor<Wrapper<ApActivity>> captor = updateCaptor();
        verify(apActivityMapper).update(any(), captor.capture());
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<ApActivity>> updateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<ApActivity>> deleteCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private static List<AdminActivityVO> listOf(ResponseResult result) {
        return (List<AdminActivityVO>) ((Map<String, Object>) result.getData()).get("list");
    }

    private static AdminActivityVO vo(ResponseResult result) {
        return (AdminActivityVO) result.getData();
    }

    private static String dayText(Date date) {
        return DAY.format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
    }

    private static Date day(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }

    /** 今天 00:00（"今天"的活动要落在进行中，靠这个基准日期） */
    private static Date truncateToToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static Date plusDays(Date base, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(base);
        c.add(Calendar.DAY_OF_YEAR, days);
        return c.getTime();
    }
}
