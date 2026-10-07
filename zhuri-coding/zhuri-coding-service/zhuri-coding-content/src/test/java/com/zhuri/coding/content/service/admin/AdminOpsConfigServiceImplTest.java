package com.zhuri.coding.content.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.circle.ApCircleHotConfigMapper;
import com.zhuri.coding.content.mapper.circle.ApCircleMapper;
import com.zhuri.coding.content.mapper.topic.TopicMapper;
import com.zhuri.coding.content.service.admin.impl.AdminOpsConfigServiceImpl;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminOpsCircleVO;
import com.zhuri.coding.model.admin.vos.AdminOpsTopicVO;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import com.zhuri.coding.model.circle.pojos.ApCircleHotConfig;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.topic.pojos.ApTopic;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 运营位配置（人气圈子 / 推荐话题）单测。
 *
 * <p>盯住四件事：
 * <ul>
 *   <li><b>落位之前先把旧位次腾空</b>：{@code display_order} 上有唯一键 {@code uniq_order}，
 *       直接按目标位次写会撞键（互换两个圈子必然撞）；</li>
 *   <li><b>清单没变要报错，不能静默成功</b>：否则审计里会多一条与上一条毫无差别的记录；</li>
 *   <li><b>不该写进库的清单要被拦住</b>：重复 id（配置表对 circle_id 没有唯一键）、
 *       不存在的圈子（没有外键，写进去只会在 C 端被静默跳过）、停用的话题（C 端 status=1 过滤）、
 *       超过位次上限（首页只展示前 5 个）；</li>
 *   <li><b>推荐话题只写真正变化的行</b>：整份重写会白刷 {@code updated_at}。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营位配置（AdminOpsConfigServiceImpl）")
class AdminOpsConfigServiceImplTest {

    private static final long CIRCLE_A = 11L;
    private static final long CIRCLE_B = 22L;
    private static final long CIRCLE_MISSING = 99L;

    @Mock
    private ApCircleMapper apCircleMapper;

    @Mock
    private ApCircleHotConfigMapper apCircleHotConfigMapper;

    @Mock
    private TopicMapper topicMapper;

    @Mock
    private AdminAuditRecorder auditRecorder;

    @InjectMocks
    private AdminOpsConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        // Lambda 包装器要靠实体元信息解析列名，单测没有 MyBatis 会话，必须先手动初始化
        // ⚠️ 漏了不会立刻报错：包装器构造时抛的异常会被 fail-closed / 空集合兜住，
        // 表现为"断言莫名其妙地失败"，而不是"元信息没初始化"。三个实体都要初始化。
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCircle.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApCircleHotConfig.class);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApTopic.class);
    }

    // ==================== 人气圈子：读 ====================

    @Test
    @DisplayName("人气圈子清单：按位次返回并带出圈子名（否则运营对着一串 id 判断不了该不该留）")
    void hotCirclesReturnsOrderedList() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(
            hotConfig(1, CIRCLE_A, 1), hotConfig(2, CIRCLE_B, 2)));
        when(apCircleMapper.selectBatchIds(any())).thenReturn(List.of(
            circle(CIRCLE_A, "前端圈", 120), circle(CIRCLE_B, "后端圈", 80)));

        ResponseResult result = service.hotCircles();

        assertEquals(200, result.getCode().intValue());
        List<AdminOpsCircleVO> list = listOf(result);
        assertEquals(2, list.size());
        assertEquals(CIRCLE_A, list.get(0).getCircleId());
        assertEquals("前端圈", list.get(0).getName());
        assertEquals(1, list.get(0).getDisplayOrder().intValue());
        assertEquals(CIRCLE_B, list.get(1).getCircleId());
        assertEquals(2, list.get(1).getDisplayOrder().intValue());
    }

    @Test
    @DisplayName("人气圈子清单：配置指向已删除的圈子时跳过（不造空壳行，也不抛异常）")
    void hotCirclesSkipsDanglingConfig() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(
            hotConfig(1, CIRCLE_A, 1), hotConfig(2, CIRCLE_MISSING, 2)));
        when(apCircleMapper.selectBatchIds(any())).thenReturn(List.of(circle(CIRCLE_A, "前端圈", 120)));

        List<AdminOpsCircleVO> list = listOf(service.hotCircles());

        assertEquals(1, list.size());
        assertEquals(CIRCLE_A, list.get(0).getCircleId());
    }

    @Test
    @DisplayName("人气圈子清单：没有任何配置时返回空列表，且不去查圈子表（空集合会拼出 IN () 非法 SQL）")
    void hotCirclesEmptyDoesNotQueryCircles() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertTrue(listOf(service.hotCircles()).isEmpty());
        verify(apCircleMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("圈子候选：标出已在人气位的位次，并把 size 收口到上限")
    void searchCirclesMarksConfiguredAndClampsSize() {
        Page<ApCircle> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of(circle(CIRCLE_A, "前端圈", 120), circle(CIRCLE_B, "后端圈", 80)));
        dbPage.setTotal(2);
        when(apCircleMapper.selectPage(any(), any())).thenReturn(dbPage);
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(hotConfig(1, CIRCLE_A, 3)));

        ResponseResult result = service.searchCircles(null, 0, 100000);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2L, data.get("total"));
        assertEquals(1, data.get("page"), "page < 1 归一为 1");
        assertEquals(20, data.get("size"), "size 越界必须回落到默认值，不能按调用方给的值拉数据");
        List<AdminOpsCircleVO> list = listOf(result);
        assertEquals(3, list.get(0).getDisplayOrder().intValue(), "已上人气位的圈子要标出位次");
        assertNull(list.get(1).getDisplayOrder(), "未上人气位的圈子位次为 null");
    }

    // ==================== 人气圈子：写 ====================

    @Test
    @DisplayName("保存人气圈子：数组顺序即位次，先腾位再落位，前后快照写进审计")
    void saveHotCirclesRelocatesAndAudits() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(hotConfig(7, CIRCLE_A, 1)));
        when(apCircleMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream()
                .map(id -> circle(id, id == CIRCLE_A ? "前端圈" : "后端圈", 10))
                .collect(Collectors.toList());
        });

        ResponseResult result = service.saveHotCircles(List.of(CIRCLE_B, CIRCLE_A), "运营位调整");

        assertEquals(200, result.getCode().intValue());
        List<AdminOpsCircleVO> list = listOf(result);
        assertEquals(CIRCLE_B, list.get(0).getCircleId());
        assertEquals(1, list.get(0).getDisplayOrder().intValue());
        assertEquals(CIRCLE_A, list.get(1).getCircleId());
        assertEquals(2, list.get(1).getDisplayOrder().intValue());

        // 第一次写入必须是"把旧位次挪出目标区间"：display_order 上有唯一键 uniq_order，
        // 不腾位直接落位，遇到两个圈子互换位次就会撞键
        ArgumentCaptor<Wrapper<ApCircleHotConfig>> updates = circleUpdateCaptor();
        verify(apCircleHotConfigMapper, times(2)).update(any(), updates.capture());
        assertTrue(((LambdaUpdateWrapper<?>) updates.getAllValues().get(0)).getSqlSet().contains("-id"),
            "落位前必须先把旧位次腾空（display_order = -id）");
        assertTrue(((LambdaUpdateWrapper<?>) updates.getAllValues().get(1))
                .getParamNameValuePairs().containsValue(2),
            "保留的圈子要按行 id 写回第 2 位（表上 circle_id 没有唯一键，不能按它定位整批重写）");

        ArgumentCaptor<ApCircleHotConfig> inserted = ArgumentCaptor.forClass(ApCircleHotConfig.class);
        verify(apCircleHotConfigMapper).insert(inserted.capture());
        assertEquals(CIRCLE_B, inserted.getValue().getCircleId());
        assertEquals(1, inserted.getValue().getDisplayOrder().intValue());

        ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(audit.capture());
        assertEquals(ApAdminAuditLog.MODULE_OPS, audit.getValue().getModule());
        assertEquals(AdminOpsConfigService.ACTION_SET_HOT_CIRCLES, audit.getValue().getAction());
        assertEquals("运营位调整", audit.getValue().getReason());
        assertEquals("人气圈子 [1:前端圈] -> [1:后端圈, 2:前端圈]", audit.getValue().getDetail());
    }

    @Test
    @DisplayName("保存人气圈子：清单没变时明确报错，不落库、不写成功审计")
    void saveHotCirclesRejectsUnchanged() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(
            hotConfig(1, CIRCLE_A, 1), hotConfig(2, CIRCLE_B, 2)));
        when(apCircleMapper.selectBatchIds(any())).thenReturn(List.of(
            circle(CIRCLE_A, "前端圈", 120), circle(CIRCLE_B, "后端圈", 80)));

        ResponseResult result = service.saveHotCircles(List.of(CIRCLE_A, CIRCLE_B), "手滑点了一下保存");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("一致"), "要说清是没变化：" + result.getMessage());
        verify(apCircleHotConfigMapper, never()).update(any(), any());
        verify(apCircleHotConfigMapper, never()).insert(any(ApCircleHotConfig.class));
        // ⚠️ 匹配器必须写死类型：BaseMapper 上同时有 deleteById(Serializable) 与 deleteById(T)，
        // 裸 any() 会挑中更具体的 deleteById(T) —— 那条重载根本不会被调用，
        // 断言就成了"永远成立"的假绿灯（同理 insert(T) / insert(Collection<T>)）。
        verify(apCircleHotConfigMapper, never()).deleteById(any(Serializable.class));
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("保存人气圈子：包含不存在的圈子时拒绝（没有外键，写进去只会被 C 端静默跳过）")
    void saveHotCirclesRejectsMissingCircle() {
        when(apCircleMapper.selectBatchIds(any())).thenReturn(List.of(circle(CIRCLE_A, "前端圈", 120)));

        ResponseResult result = service.saveHotCircles(List.of(CIRCLE_A, CIRCLE_MISSING), "加个圈子");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("不存在"));
        verify(apCircleHotConfigMapper, never()).insert(any(ApCircleHotConfig.class));
        verify(apCircleHotConfigMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("保存人气圈子：重复 id 被拦住（配置表对 circle_id 没有唯一键，重复会真的插两行）")
    void saveHotCirclesRejectsDuplicate() {
        ResponseResult result = service.saveHotCircles(List.of(CIRCLE_A, CIRCLE_A), "重复了");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("重复"));
        verifyNoInteractions(apCircleMapper, apCircleHotConfigMapper);
    }

    @Test
    @DisplayName("保存人气圈子：非法 id（null / 非正数）被拦住，且不打库")
    void saveHotCirclesRejectsInvalidId() {
        ResponseResult zero = service.saveHotCircles(List.of(0L), "写错了");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), zero.getCode().intValue());

        List<Long> withNull = new ArrayList<>();
        withNull.add(CIRCLE_A);
        withNull.add(null);
        ResponseResult hasNull = service.saveHotCircles(withNull, "写错了");
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), hasNull.getCode().intValue());

        verifyNoInteractions(apCircleMapper, apCircleHotConfigMapper);
    }

    @Test
    @DisplayName("保存人气圈子：超过位次上限时拒绝（首页只展示前 5 个，多配就是配了不展示）")
    void saveHotCirclesRejectsOverLimit() {
        ResponseResult result = service.saveHotCircles(
            List.of(1L, 2L, 3L, 4L, 5L, 6L), "加多了");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("最多"));
        verifyNoInteractions(apCircleMapper, apCircleHotConfigMapper);
    }

    @Test
    @DisplayName("保存人气圈子：空清单是合法的「清空」——旧行全删、不插新行")
    void saveHotCirclesAllowsClearing() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(
            hotConfig(1, CIRCLE_A, 1), hotConfig(2, CIRCLE_B, 2)));
        when(apCircleMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> circle(id, "圈子" + id, 10)).collect(Collectors.toList());
        });

        ResponseResult result = service.saveHotCircles(new ArrayList<>(), "临时下掉整个运营位");

        assertEquals(200, result.getCode().intValue());
        assertTrue(listOf(result).isEmpty());
        verify(apCircleHotConfigMapper).deleteById(1);
        verify(apCircleHotConfigMapper).deleteById(2);
        verify(apCircleHotConfigMapper, never()).insert(any(ApCircleHotConfig.class));
        verify(apCircleHotConfigMapper, times(1)).update(any(), any());

        ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(audit.capture());
        assertTrue(audit.getValue().getDetail().endsWith("[空]"), audit.getValue().getDetail());
    }

    @Test
    @DisplayName("保存人气圈子：抛异常时业务回滚，并记一条失败审计（谁试过但没成功也要能看到）")
    void saveHotCirclesRecordsFailureOnException() {
        when(apCircleHotConfigMapper.selectList(any())).thenReturn(List.of(hotConfig(7, CIRCLE_A, 1)));
        when(apCircleMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> circle(id, "圈子" + id, 10)).collect(Collectors.toList());
        });
        when(apCircleHotConfigMapper.insert(any(ApCircleHotConfig.class))).thenThrow(new RuntimeException("Duplicate entry '-25'"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> service.saveHotCircles(List.of(CIRCLE_B, CIRCLE_A), "调整位次"));

        assertEquals("Duplicate entry '-25'", thrown.getMessage());
        ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordFailure(audit.capture(), eq("Duplicate entry '-25'"));
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("位次上限与读路径共用同一个常量（分开写死就会出现「配了第 6 个却永远看不见」）")
    void hotCircleLimitIsSharedWithReadPath() {
        assertEquals(5, ApCircleHotConfig.MAX_DISPLAY_ORDER);
        assertEquals(ApCircleHotConfig.MAX_DISPLAY_ORDER, AdminOpsConfigService.MAX_HOT_CIRCLES);
    }

    // ==================== 推荐话题 ====================

    @Test
    @DisplayName("推荐话题清单：停用的话题也要列出来（C 端看不见它，只有这里能发现它白占一个位次）")
    void recommendTopicsIncludesDisabled() {
        when(topicMapper.selectList(any())).thenReturn(List.of(
            topic(1, "Java", 1, 1), topic(2, "Golang", 0, 2)));

        List<AdminOpsTopicVO> list = listOf(service.recommendTopics());

        assertEquals(2, list.size());
        assertEquals("Golang", list.get(1).getName());
        assertEquals(0, list.get(1).getStatus().intValue());
        assertEquals(2, list.get(1).getRecommendOrder().intValue());
    }

    @Test
    @DisplayName("话题候选：标出已推荐位次，未推荐的位次为 null")
    void searchTopicsMarksRecommended() {
        Page<ApTopic> dbPage = new Page<>(1, 20);
        dbPage.setRecords(List.of(topic(1, "Java", 1, 5), topic(2, "Golang", 1, 9)));
        dbPage.setTotal(2);
        when(topicMapper.selectPage(any(), any())).thenReturn(dbPage);
        when(topicMapper.selectList(any())).thenReturn(List.of(topic(1, "Java", 1, 5)));

        List<AdminOpsTopicVO> list = listOf(service.searchTopics("J", 1, 20));

        assertEquals(5, list.get(0).getRecommendOrder().intValue());
        assertNull(list.get(1).getRecommendOrder(), "未上推荐位的话题位次为 null");
    }

    @Test
    @DisplayName("保存推荐话题：先下位再落位，只写真正变化的行，前后快照写进审计")
    void saveRecommendTopicsTurnsOffOthersAndRenumbers() {
        ApTopic java = topic(1, "Java", 1, 1);
        ApTopic golang = topic(2, "Golang", 1, 2);
        when(topicMapper.selectList(any())).thenReturn(List.of(java, golang));
        when(topicMapper.selectBatchIds(any())).thenReturn(List.of(java, golang));

        ResponseResult result = service.saveRecommendTopics(List.of(2L, 1L), "换一下顺序");

        assertEquals(200, result.getCode().intValue());
        List<AdminOpsTopicVO> list = listOf(result);
        assertEquals(2L, list.get(0).getTopicId());
        assertEquals(1, list.get(0).getRecommendOrder().intValue());
        assertEquals(1L, list.get(1).getTopicId());
        assertEquals(2, list.get(1).getRecommendOrder().intValue());

        ArgumentCaptor<Wrapper<ApTopic>> updates = topicUpdateCaptor();
        verify(topicMapper, times(3)).update(any(), updates.capture());
        LambdaUpdateWrapper<?> off = (LambdaUpdateWrapper<?>) updates.getAllValues().get(0);
        assertTrue(off.getSqlSet().contains("is_recommend"), "第一步是把不在目标清单里的清下推荐位");
        assertTrue(off.getSqlSegment().contains("NOT IN"), off.getSqlSegment());

        Set<Object> written = new HashSet<>();
        for (int i = 1; i < 3; i++) {
            written.addAll(((LambdaUpdateWrapper<?>) updates.getAllValues().get(i))
                .getParamNameValuePairs().values());
        }
        assertTrue(written.contains(1) && written.contains(2), "两行都要写回目标位次：" + written);
        verify(topicMapper, never()).insert(any(ApTopic.class));

        ArgumentCaptor<ApAdminAuditLog> audit = ArgumentCaptor.forClass(ApAdminAuditLog.class);
        verify(auditRecorder).recordSuccess(audit.capture());
        assertEquals(AdminOpsConfigService.ACTION_SET_RECOMMEND_TOPICS, audit.getValue().getAction());
        assertEquals("推荐话题 [1:Java, 2:Golang] -> [1:Golang, 2:Java]", audit.getValue().getDetail());
    }

    @Test
    @DisplayName("保存推荐话题：位次没变的行不写（整份重写会把 updated_at 白刷一遍）")
    void saveRecommendTopicsSkipsUnchangedRows() {
        when(topicMapper.selectList(any())).thenReturn(List.of(
            topic(1, "Java", 1, 1), topic(2, "Golang", 1, 2)));
        when(topicMapper.selectBatchIds(any())).thenReturn(List.of(
            topic(1, "Java", 1, 1), topic(2, "Golang", 1, 2), topic(3, "Rust", 1, 0)));

        ResponseResult result = service.saveRecommendTopics(List.of(1L, 2L, 3L), "加一个");

        assertEquals(200, result.getCode().intValue());
        // 一次"下位" + 只有第 3 个话题需要写（前两个位次没动）
        verify(topicMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("保存推荐话题：空清单=清空推荐位，且 where 不能带空集合的 NOT IN")
    void saveRecommendTopicsClearsAll() {
        when(topicMapper.selectList(any())).thenReturn(List.of(
            topic(1, "Java", 1, 1), topic(2, "Golang", 1, 2)));

        ResponseResult result = service.saveRecommendTopics(new ArrayList<>(), "临时清空");

        assertEquals(200, result.getCode().intValue());
        assertTrue(listOf(result).isEmpty());

        ArgumentCaptor<Wrapper<ApTopic>> updates = topicUpdateCaptor();
        verify(topicMapper, times(1)).update(any(), updates.capture());
        String where = ((LambdaUpdateWrapper<?>) updates.getValue()).getSqlSegment();
        assertTrue(where.contains("is_recommend"), where);
        assertFalse(where.contains("NOT IN"),
            "清空时不能带 NOT IN (空集合)：一条都下不掉，还会拼出非法 SQL");
    }

    @Test
    @DisplayName("保存推荐话题：停用的话题被拒绝并指出是哪一个（C 端按 status=1 过滤，配了也不展示）")
    void saveRecommendTopicsRejectsDisabledTopic() {
        when(topicMapper.selectBatchIds(any())).thenReturn(List.of(
            topic(1, "Java", 1, 0), topic(2, "Golang", 0, 0)));

        ResponseResult result = service.saveRecommendTopics(List.of(1L, 2L), "加两个");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("停用"), result.getMessage());
        assertTrue(result.getMessage().contains("2"), "要说清是哪个话题：" + result.getMessage());
        verify(topicMapper, never()).update(any(), any());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("保存推荐话题：清单没变时明确报错")
    void saveRecommendTopicsRejectsUnchanged() {
        when(topicMapper.selectList(any())).thenReturn(List.of(
            topic(1, "Java", 1, 1), topic(2, "Golang", 1, 2)));
        when(topicMapper.selectBatchIds(any())).thenReturn(List.of(
            topic(1, "Java", 1, 1), topic(2, "Golang", 1, 2)));

        ResponseResult result = service.saveRecommendTopics(List.of(1L, 2L), "手滑");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("一致"));
        verify(topicMapper, never()).update(any(), any());
        verify(auditRecorder, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("保存推荐话题：话题不存在时拒绝")
    void saveRecommendTopicsRejectsMissingTopic() {
        when(topicMapper.selectBatchIds(any())).thenReturn(List.of(topic(1, "Java", 1, 0)));

        ResponseResult result = service.saveRecommendTopics(List.of(1L, 2L), "加两个");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("保存推荐话题：超过上限时拒绝，且不打库")
    void saveRecommendTopicsRejectsOverLimit() {
        List<Long> ids = LongStream.rangeClosed(1, AdminOpsConfigService.MAX_RECOMMEND_TOPICS + 1)
            .boxed().collect(Collectors.toList());

        ResponseResult result = service.saveRecommendTopics(ids, "加多了");

        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), result.getCode().intValue());
        assertTrue(result.getMessage().contains("最多"));
        verifyNoInteractions(topicMapper);
    }

    // ==================== 辅助 ====================

    private static ApCircle circle(long id, String name, int memberCount) {
        ApCircle circle = new ApCircle();
        circle.setId(id);
        circle.setName(name);
        circle.setMemberCount(memberCount);
        circle.setPinsCount(0);
        return circle;
    }

    private static ApCircleHotConfig hotConfig(int rowId, long circleId, int displayOrder) {
        ApCircleHotConfig config = new ApCircleHotConfig();
        config.setId(rowId);
        config.setCircleId(circleId);
        config.setDisplayOrder(displayOrder);
        return config;
    }

    private static ApTopic topic(long id, String name, int status, int recommendSort) {
        ApTopic topic = new ApTopic();
        topic.setId(id);
        topic.setName(name);
        topic.setStatus(status);
        topic.setIsRecommend(recommendSort > 0 ? 1 : 0);
        topic.setRecommendSort(recommendSort);
        return topic;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> listOf(ResponseResult result) {
        return (List<T>) ((Map<String, Object>) result.getData()).get("list");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<ApCircleHotConfig>> circleUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<ApTopic>> topicUpdateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
