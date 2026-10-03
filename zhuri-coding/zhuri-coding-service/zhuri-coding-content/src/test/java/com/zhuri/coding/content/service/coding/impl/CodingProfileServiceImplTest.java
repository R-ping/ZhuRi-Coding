package com.zhuri.coding.content.service.coding.impl;

import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAssessmentMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingProfileSettingMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.model.coding.dtos.CodingProfileSettingDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAssessment;
import com.zhuri.coding.model.coding.pojos.ApCodingProfileSetting;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingAbilityProfileVO;
import com.zhuri.coding.model.coding.vos.CodingProfileSettingVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingProfileServiceImpl 单元测试（Coding 延展第二层 · Stage A 能力档案）
 *
 * 覆盖：本人全量档案（五块数据与排序）、访客整体未公开（不泄露数据）、
 * 访客分项裁剪（未公开块占位/已公开块正常）、无记录默认值降级（含 reward 不可用）、
 * 隐私开关读取/保存（新建/更新/并发唯一键兜底）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("能力档案服务单元测试")
class CodingProfileServiceImplTest {

    private static final Integer USER_ID = 1001;

    @Mock
    private ApCodingUserStatMapper statMapper;
    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingAssessmentMapper assessmentMapper;
    @Mock
    private ApCodingProfileSettingMapper settingMapper;
    @Mock
    private ApArticleMapper articleMapper;
    @Mock
    private ApCollectionMapper collectionMapper;
    @Mock
    private IRewardClient rewardClient;
    @Mock
    private IUserClient userClient;

    @InjectMocks
    private CodingProfileServiceImpl service;

    // ---------- 辅助 ----------

    private ApCodingProfileSetting setting(int isPublic, int domain, int streak, int output, int assessment) {
        ApCodingProfileSetting s = new ApCodingProfileSetting();
        s.setId(1L);
        s.setUserId(USER_ID);
        s.setIsPublic(isPublic);
        s.setPublicDomain(domain);
        s.setPublicStreak(streak);
        s.setPublicOutput(output);
        s.setPublicSolve(0);
        s.setPublicAssessment(assessment);
        return s;
    }

    private ApCodingUserStat stat(String tagStats) {
        ApCodingUserStat s = new ApCodingUserStat();
        s.setId(1L);
        s.setUserId(USER_ID);
        s.setTagStats(tagStats);
        s.setFirstAnswerDate(Date.valueOf(LocalDate.of(2026, 9, 1)));
        s.setLastAnswerDate(Date.valueOf(LocalDate.of(2026, 10, 3)));
        return s;
    }

    private void stubUserBrief() {
        Map<String, Object> info = new HashMap<>();
        info.put("nickname", "张三");
        info.put("avatar", "a.png");
        when(userClient.getPublicInfo(USER_ID.longValue())).thenReturn(ResponseResult.okResult(info));
    }

    private void stubContinuousDays(int days) {
        Map<String, Object> data = new HashMap<>();
        data.put("continuousDays", days);
        when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
            .thenReturn(ResponseResult.okResult(data));
    }

    private CodingAbilityProfileVO profileOf(ResponseResult result) {
        return (CodingAbilityProfileVO) result.getData();
    }

    // ==================== 档案：本人视角 ====================

    @Test
    @DisplayName("本人视角 - 五块全量返回，领域按答题量降序")
    void testSelfFullProfile() {
        when(settingMapper.selectOne(any())).thenReturn(setting(1, 1, 1, 1, 1));
        stubUserBrief();
        when(statMapper.selectOne(any())).thenReturn(stat(
            "{\"Redis\":{\"total\":2,\"correct\":1},\"MySQL\":{\"total\":5,\"correct\":4}}"));
        stubContinuousDays(5);
        when(recordMapper.countActiveMonths(USER_ID)).thenReturn(2);
        when(articleMapper.selectCount(any())).thenReturn(3L);
        when(collectionMapper.countCollectedByArticleAuthor(USER_ID)).thenReturn(4);
        ApCodingAssessment latest = new ApCodingAssessment();
        latest.setScore(80);
        latest.setCorrectCount(8);
        latest.setTotalCount(10);
        latest.setPercentile(85);
        latest.setSubmittedTime(java.util.Date.from(
            java.time.LocalDateTime.of(2026, 10, 1, 20, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant()));
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(latest);

        ResponseResult result = service.profile(USER_ID, USER_ID);

        assertEquals(200, result.getCode().intValue());
        CodingAbilityProfileVO vo = profileOf(result);
        assertEquals("self", vo.getViewer());
        assertTrue(vo.getIsPublic());
        assertEquals("张三", vo.getNickname());

        CodingAbilityProfileVO.Blocks blocks = vo.getBlocks();
        // 领域：5 条在 Redis 前（按 total 降序），上限 8
        assertEquals(2, blocks.getDomain().getItems().size());
        assertEquals("MySQL", blocks.getDomain().getItems().get(0).getTag());
        assertEquals(5, blocks.getDomain().getItems().get(0).getTotal());
        assertTrue(blocks.getDomain().getAvailable());
        // 持续度
        assertEquals(5, blocks.getStreak().getContinuousDays());
        assertEquals(2, blocks.getStreak().getActiveMonths());
        assertEquals("2026-09-01", blocks.getStreak().getFirstAnswerDate());
        assertTrue(blocks.getStreak().getAvailable());
        // 输出
        assertEquals(3, blocks.getOutput().getArticleCount());
        assertEquals(4, blocks.getOutput().getCollectedCount());
        assertTrue(blocks.getOutput().getAvailable());
        // 测评
        assertEquals(80, blocks.getAssessment().getScore());
        assertEquals(85, blocks.getAssessment().getPercentile());
        assertTrue(blocks.getAssessment().getAvailable());
        // 解决问题：依赖付费问答，恒不可用
        assertFalse(blocks.getSolve().getAvailable());
    }

    @Test
    @DisplayName("本人视角 - 分项关闭仅影响回显标记，数据仍全量可见")
    void testSelfBlockSwitchOffStillVisibleToSelf() {
        when(settingMapper.selectOne(any())).thenReturn(setting(1, 1, 0, 1, 1));
        stubUserBrief();
        when(statMapper.selectOne(any())).thenReturn(stat("{\"Redis\":{\"total\":2,\"correct\":1}}"));
        stubContinuousDays(5);
        when(recordMapper.countActiveMonths(USER_ID)).thenReturn(1);
        when(articleMapper.selectCount(any())).thenReturn(0L);
        when(collectionMapper.countCollectedByArticleAuthor(USER_ID)).thenReturn(0);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);

        CodingAbilityProfileVO vo = profileOf(service.profile(USER_ID, USER_ID));

        assertFalse(vo.getBlocks().getStreak().getPublicVisible());
        assertEquals(5, vo.getBlocks().getStreak().getContinuousDays());
    }

    @Test
    @DisplayName("本人视角 - 无设置记录时按默认回显（整体私有、分项公开），无数据块降级")
    void testSelfDefaultsWhenNoSettingRow() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        stubUserBrief();
        when(statMapper.selectOne(any())).thenReturn(null);
        when(rewardClient.getContinuousCheckinDays(anyLong())).thenThrow(new RuntimeException("reward down"));
        when(recordMapper.countActiveMonths(USER_ID)).thenReturn(0);
        when(articleMapper.selectCount(any())).thenReturn(0L);
        when(collectionMapper.countCollectedByArticleAuthor(USER_ID)).thenReturn(0);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);

        CodingAbilityProfileVO vo = profileOf(service.profile(USER_ID, USER_ID));

        assertFalse(vo.getIsPublic());
        assertTrue(vo.getBlocks().getDomain().getPublicVisible());
        assertTrue(vo.getBlocks().getStreak().getPublicVisible());
        assertTrue(vo.getBlocks().getOutput().getPublicVisible());
        assertTrue(vo.getBlocks().getAssessment().getPublicVisible());
        // 无数据：各块 available=false；reward 不可用降级 0
        assertFalse(vo.getBlocks().getDomain().getAvailable());
        assertFalse(vo.getBlocks().getStreak().getAvailable());
        assertEquals(0, vo.getBlocks().getStreak().getContinuousDays());
        assertFalse(vo.getBlocks().getOutput().getAvailable());
        assertFalse(vo.getBlocks().getAssessment().getAvailable());
    }

    @Test
    @DisplayName("本人视角 - 领域分布最多展示 8 个（按答题量降序截断）")
    void testSelfDomainTopLimit() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        stubUserBrief();
        StringBuilder json = new StringBuilder("{");
        for (int i = 1; i <= 9; i++) {
            json.append("\"T").append(i).append("\":{\"total\":").append(i).append(",\"correct\":1}");
            if (i < 9) {
                json.append(",");
            }
        }
        json.append("}");
        when(statMapper.selectOne(any())).thenReturn(stat(json.toString()));
        stubContinuousDays(0);
        when(recordMapper.countActiveMonths(USER_ID)).thenReturn(1);
        when(articleMapper.selectCount(any())).thenReturn(0L);
        when(collectionMapper.countCollectedByArticleAuthor(USER_ID)).thenReturn(0);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);

        CodingAbilityProfileVO vo = profileOf(service.profile(USER_ID, USER_ID));

        assertEquals(8, vo.getBlocks().getDomain().getItems().size());
        assertEquals("T9", vo.getBlocks().getDomain().getItems().get(0).getTag());
    }

    // ==================== 档案：访客视角 ====================

    @Test
    @DisplayName("访客视角 - 整体未公开只返回空态，不读任何业务数据")
    void testVisitorWholePrivate() {
        when(settingMapper.selectOne(any())).thenReturn(setting(0, 1, 1, 1, 1));
        stubUserBrief();

        CodingAbilityProfileVO vo = profileOf(service.profile(USER_ID, null));

        assertEquals("visitor", vo.getViewer());
        assertFalse(vo.getIsPublic());
        assertFalse(vo.getBlocks().getDomain().getAvailable());
        assertFalse(vo.getBlocks().getStreak().getAvailable());
        assertFalse(vo.getBlocks().getOutput().getAvailable());
        assertFalse(vo.getBlocks().getAssessment().getAvailable());
        verifyNoInteractions(statMapper, recordMapper, assessmentMapper, articleMapper,
            collectionMapper, rewardClient);
    }

    @Test
    @DisplayName("访客视角 - 已公开按分项裁剪：关闭块占位、开启块正常")
    void testVisitorPartialCut() {
        when(settingMapper.selectOne(any())).thenReturn(setting(1, 1, 0, 1, 1));
        stubUserBrief();
        when(statMapper.selectOne(any())).thenReturn(stat("{\"Redis\":{\"total\":2,\"correct\":1}}"));
        when(articleMapper.selectCount(any())).thenReturn(2L);
        when(collectionMapper.countCollectedByArticleAuthor(USER_ID)).thenReturn(1);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);

        CodingAbilityProfileVO vo = profileOf(service.profile(USER_ID, 9999));

        // 持续度关闭：占位（available=true, public=false），数据未下发且不查询
        assertTrue(vo.getBlocks().getStreak().getAvailable());
        assertFalse(vo.getBlocks().getStreak().getPublicVisible());
        assertNull(vo.getBlocks().getStreak().getContinuousDays());
        verify(recordMapper, never()).countActiveMonths(any());
        verify(rewardClient, never()).getContinuousCheckinDays(anyLong());
        // 其余块正常下发
        assertTrue(vo.getBlocks().getDomain().getAvailable());
        assertEquals("Redis", vo.getBlocks().getDomain().getItems().get(0).getTag());
        assertEquals(2, vo.getBlocks().getOutput().getArticleCount());
        assertFalse(vo.getBlocks().getAssessment().getAvailable());
    }

    @Test
    @DisplayName("访客视角 - 本人查看自己未公开档案仍返回全量（viewer=self）")
    void testSelfSeesPrivateProfile() {
        when(settingMapper.selectOne(any())).thenReturn(setting(0, 1, 1, 1, 1));
        stubUserBrief();
        when(statMapper.selectOne(any())).thenReturn(null);
        stubContinuousDays(3);
        when(recordMapper.countActiveMonths(USER_ID)).thenReturn(1);
        when(articleMapper.selectCount(any())).thenReturn(1L);
        when(collectionMapper.countCollectedByArticleAuthor(USER_ID)).thenReturn(0);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);

        CodingAbilityProfileVO vo = profileOf(service.profile(USER_ID, USER_ID));

        assertEquals("self", vo.getViewer());
        assertFalse(vo.getIsPublic());
        assertTrue(vo.getBlocks().getOutput().getAvailable());
        assertEquals(3, vo.getBlocks().getStreak().getContinuousDays());
    }

    // ==================== 档案：参数校验 ====================

    @Test
    @DisplayName("参数校验 - 用户ID非法返回 PARAM_INVALID")
    void testProfileInvalidUser() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.profile(null, null).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.profile(0, null).getCode());
        verifyNoInteractions(settingMapper, statMapper, articleMapper, collectionMapper);
    }

    // ==================== 隐私开关 ====================

    @Test
    @DisplayName("读取开关 - 无记录返回默认值（整体私有、分项公开、solve 预留关闭）")
    void testGetSettingDefault() {
        when(settingMapper.selectOne(any())).thenReturn(null);

        CodingProfileSettingVO vo = (CodingProfileSettingVO) service.getSetting(USER_ID).getData();

        assertFalse(vo.getIsPublic());
        assertTrue(vo.getPublicDomain());
        assertTrue(vo.getPublicStreak());
        assertTrue(vo.getPublicOutput());
        assertFalse(vo.getPublicSolve());
        assertTrue(vo.getPublicAssessment());
    }

    @Test
    @DisplayName("读取开关 - 有记录按存储值回显")
    void testGetSettingExisting() {
        when(settingMapper.selectOne(any())).thenReturn(setting(1, 0, 1, 1, 0));

        CodingProfileSettingVO vo = (CodingProfileSettingVO) service.getSetting(USER_ID).getData();

        assertTrue(vo.getIsPublic());
        assertFalse(vo.getPublicDomain());
        assertTrue(vo.getPublicStreak());
        assertFalse(vo.getPublicAssessment());
    }

    @Test
    @DisplayName("保存开关 - 首次保存新建记录，未传字段留空由默认值兜底")
    void testUpdateSettingCreate() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        CodingProfileSettingDTO dto = new CodingProfileSettingDTO();
        dto.setIsPublic(true);
        dto.setPublicStreak(false);

        CodingProfileSettingVO vo = (CodingProfileSettingVO) service.updateSetting(USER_ID, dto).getData();

        ArgumentCaptor<ApCodingProfileSetting> captor = ArgumentCaptor.forClass(ApCodingProfileSetting.class);
        verify(settingMapper).insert(captor.capture());
        verify(settingMapper, never()).updateById(any(ApCodingProfileSetting.class));
        ApCodingProfileSetting saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(1, saved.getIsPublic());
        assertEquals(0, saved.getPublicStreak());
        assertNull(saved.getPublicDomain());
        // 回显：未传字段按默认（公开）
        assertTrue(vo.getIsPublic());
        assertFalse(vo.getPublicStreak());
        assertTrue(vo.getPublicDomain());
    }

    @Test
    @DisplayName("保存开关 - 已有记录仅更新传入字段")
    void testUpdateSettingUpdate() {
        ApCodingProfileSetting existing = setting(0, 1, 1, 1, 1);
        when(settingMapper.selectOne(any())).thenReturn(existing);
        CodingProfileSettingDTO dto = new CodingProfileSettingDTO();
        dto.setPublicDomain(false);

        CodingProfileSettingVO vo = (CodingProfileSettingVO) service.updateSetting(USER_ID, dto).getData();

        verify(settingMapper).updateById(existing);
        verify(settingMapper, never()).insert(any(ApCodingProfileSetting.class));
        assertEquals(0, existing.getPublicDomain());
        assertEquals(0, existing.getIsPublic());
        assertFalse(vo.getPublicDomain());
        assertFalse(vo.getIsPublic());
    }

    @Test
    @DisplayName("保存开关 - 并发首次保存触发唯一键后回读更新")
    void testUpdateSettingDuplicateFallback() {
        ApCodingProfileSetting existing = setting(0, 1, 1, 1, 1);
        when(settingMapper.selectOne(any())).thenReturn(null, existing);
        when(settingMapper.insert(any(ApCodingProfileSetting.class)))
            .thenThrow(new DuplicateKeyException("dup"));
        CodingProfileSettingDTO dto = new CodingProfileSettingDTO();
        dto.setIsPublic(true);

        CodingProfileSettingVO vo = (CodingProfileSettingVO) service.updateSetting(USER_ID, dto).getData();

        verify(settingMapper).updateById(existing);
        assertEquals(1, existing.getIsPublic());
        assertTrue(vo.getIsPublic());
    }

    @Test
    @DisplayName("保存开关 - 参数校验：用户ID非法/入参为空返回 PARAM_INVALID")
    void testUpdateSettingInvalid() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.updateSetting(null, null).getCode());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            service.updateSetting(USER_ID, null).getCode());
        verify(settingMapper, never()).insert(any(ApCodingProfileSetting.class));
        verify(settingMapper, never()).updateById(any(ApCodingProfileSetting.class));
    }

    @Test
    @DisplayName("保存开关 - 读取参数校验：用户ID非法返回 PARAM_INVALID")
    void testGetSettingInvalid() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), service.getSetting(0).getCode());
        verify(settingMapper, never()).selectOne(any());
    }
}