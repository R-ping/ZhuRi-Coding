package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAssessmentMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingProfileSettingMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.content.service.coding.CodingProfileService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.coding.dtos.CodingProfileSettingDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAssessment;
import com.zhuri.coding.model.coding.pojos.ApCodingProfileSetting;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingAbilityProfileVO;
import com.zhuri.coding.model.coding.vos.CodingProfileSettingVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.sql.Date;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 能力档案服务实现（Coding 延展第二层 · Stage A）
 *
 * <p>五块数据来源：</p>
 * <ul>
 *   <li>技术领域分布：{@code ap_coding_user_stat.tag_stats}（答题领域 JSON）；</li>
 *   <li>持续度：签到体系连续天数（reward 服务，fail-open 0）+ 作答流水活跃月份数；</li>
 *   <li>输出能力：已发布文章数 + 我的文章被收藏数（与"我收藏的文章数"口径相反，勿混用）；</li>
 *   <li>解决问题：依赖付费问答业务，未上线前恒不可用（结构预留）；</li>
 *   <li>测评成绩：最近一次已提交测评（Stage B 产出）。</li>
 * </ul>
 *
 * <p>隐私口径：无设置记录 = 全私有；本人视角全量返回并回显开关；
 * 访客视角整体未公开只返回空态（不泄露任何数据），已公开则按分项开关逐块裁剪
 * （分项关闭的块返回 {@code available=true, public=false}，前端显示"未公开"占位）。</p>
 */
@Slf4j
@Service
public class CodingProfileServiceImpl implements CodingProfileService {

    /** 视角：本人 */
    private static final String VIEWER_SELF = "self";
    /** 视角：访客（含未登录） */
    private static final String VIEWER_VISITOR = "visitor";

    /** 领域分布展示上限（按答题量降序取前 N） */
    private static final int DOMAIN_TOP_LIMIT = 8;

    @Autowired
    private ApCodingUserStatMapper statMapper;

    @Autowired
    private ApCodingAnswerRecordMapper recordMapper;

    @Autowired
    private ApCodingAssessmentMapper assessmentMapper;

    @Autowired
    private ApCodingProfileSettingMapper settingMapper;

    @Autowired
    private ApArticleMapper articleMapper;

    @Autowired
    private ApCollectionMapper collectionMapper;

    @Autowired
    private IRewardClient rewardClient;

    @Autowired
    private IUserClient userClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ResponseResult profile(Integer targetUserId, Integer viewerUserId) {
        if (targetUserId == null || targetUserId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "用户ID不能为空");
        }
        boolean self = viewerUserId != null && viewerUserId.equals(targetUserId);
        ApCodingProfileSetting setting = loadSetting(targetUserId);
        boolean profilePublic = setting != null && isOn(setting.getIsPublic());

        CodingAbilityProfileVO vo = new CodingAbilityProfileVO();
        vo.setUserId(targetUserId);
        vo.setViewer(self ? VIEWER_SELF : VIEWER_VISITOR);
        vo.setIsPublic(profilePublic);
        fillUserBrief(vo, targetUserId);

        CodingAbilityProfileVO.Blocks blocks = new CodingAbilityProfileVO.Blocks();
        vo.setBlocks(blocks);

        // 访客且整体未公开：五块保持默认空态，前端按 isPublic=false 显示整体占位（不泄露任何数据）
        if (!self && !profilePublic) {
            return ResponseResult.okResult(vo);
        }

        ApCodingUserStat stat = loadStat(targetUserId);
        buildDomainBlock(blocks.getDomain(), stat, self, setting);
        buildStreakBlock(blocks.getStreak(), stat, targetUserId, self, setting);
        buildOutputBlock(blocks.getOutput(), targetUserId, self, setting);
        buildAssessmentBlock(blocks.getAssessment(), targetUserId, self, setting);
        // 解决问题块：依赖付费问答业务，未上线前恒不可用（结构预留）
        return ResponseResult.okResult(vo);
    }

    @Override
    public ResponseResult getSetting(Integer userId) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "用户ID不能为空");
        }
        return ResponseResult.okResult(toSettingVO(loadSetting(userId)));
    }

    @Override
    public ResponseResult updateSetting(Integer userId, CodingProfileSettingDTO dto) {
        if (userId == null || userId <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "用户ID不能为空");
        }
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        ApCodingProfileSetting setting = loadSetting(userId);
        if (setting == null) {
            // 首次保存：未传字段依赖数据库默认值（整体私有、分项公开），内存对象同样按 null 判默认
            setting = new ApCodingProfileSetting();
            setting.setUserId(userId);
            applyDto(setting, dto);
            try {
                settingMapper.insert(setting);
            } catch (DuplicateKeyException e) {
                // 并发首次保存触发唯一键：回读后按更新处理（与第一层统计 upsert 同思路）
                setting = loadSetting(userId);
                applyDto(setting, dto);
                settingMapper.updateById(setting);
            }
        } else {
            applyDto(setting, dto);
            settingMapper.updateById(setting);
        }
        return ResponseResult.okResult(toSettingVO(setting));
    }

    // ==================== 档案块组装 ====================

    private void buildDomainBlock(CodingAbilityProfileVO.DomainBlock block, ApCodingUserStat stat,
                                  boolean self, ApCodingProfileSetting setting) {
        boolean publicVisible = setting == null || setting.domainPublic();
        block.setPublicVisible(publicVisible);
        if (!self && !publicVisible) {
            // 分项未公开：只告知"该块存在但未公开"，不下发数据
            block.setAvailable(true);
            return;
        }
        List<CodingAbilityProfileVO.DomainItem> items = parseDomainItems(
            stat == null ? null : stat.getTagStats());
        block.setItems(items);
        block.setAvailable(!items.isEmpty());
    }

    private void buildStreakBlock(CodingAbilityProfileVO.StreakBlock block, ApCodingUserStat stat,
                                  Integer userId, boolean self, ApCodingProfileSetting setting) {
        boolean publicVisible = setting == null || setting.streakPublic();
        block.setPublicVisible(publicVisible);
        if (!self && !publicVisible) {
            block.setAvailable(true);
            return;
        }
        int continuousDays = currentContinuousDays(userId);
        int activeMonths = recordMapper.countActiveMonths(userId);
        block.setContinuousDays(continuousDays);
        block.setActiveMonths(activeMonths);
        if (stat != null) {
            block.setFirstAnswerDate(toDateString(stat.getFirstAnswerDate()));
            block.setLastAnswerDate(toDateString(stat.getLastAnswerDate()));
        }
        block.setAvailable((stat != null && stat.getFirstAnswerDate() != null) || continuousDays > 0);
    }

    private void buildOutputBlock(CodingAbilityProfileVO.OutputBlock block, Integer userId,
                                  boolean self, ApCodingProfileSetting setting) {
        boolean publicVisible = setting == null || setting.outputPublic();
        block.setPublicVisible(publicVisible);
        if (!self && !publicVisible) {
            block.setAvailable(true);
            return;
        }
        long articleCount = articleMapper.selectCount(new LambdaQueryWrapper<ApArticle>()
            .eq(ApArticle::getAuthorId, userId.longValue())
            .eq(ApArticle::getIsDeleted, false)
            .eq(ApArticle::getStatus, ApArticle.Status.PUBLISHED.getCode()));
        int collectedCount = collectionMapper.countCollectedByArticleAuthor(userId);
        block.setArticleCount((int) articleCount);
        block.setCollectedCount(collectedCount);
        block.setAvailable(articleCount > 0 || collectedCount > 0);
    }

    private void buildAssessmentBlock(CodingAbilityProfileVO.AssessmentBlock block, Integer userId,
                                      boolean self, ApCodingProfileSetting setting) {
        boolean publicVisible = setting == null || setting.assessmentPublic();
        block.setPublicVisible(publicVisible);
        if (!self && !publicVisible) {
            block.setAvailable(true);
            return;
        }
        ApCodingAssessment latest = assessmentMapper.selectLatestSubmitted(userId);
        if (latest == null) {
            return; // available=false：前端显示"暂未测评"
        }
        block.setAvailable(true);
        block.setScore(latest.getScore());
        block.setCorrectCount(latest.getCorrectCount());
        block.setTotalCount(latest.getTotalCount());
        block.setPercentile(latest.getPercentile());
        block.setSubmittedTime(toDateTimeString(latest.getSubmittedTime()));
    }

    // ==================== 内部方法 ====================

    private ApCodingProfileSetting loadSetting(Integer userId) {
        return settingMapper.selectOne(new LambdaQueryWrapper<ApCodingProfileSetting>()
            .eq(ApCodingProfileSetting::getUserId, userId));
    }

    private ApCodingUserStat loadStat(Integer userId) {
        return statMapper.selectOne(new LambdaQueryWrapper<ApCodingUserStat>()
            .eq(ApCodingUserStat::getUserId, userId));
    }

    /** 昵称头像（用户服务不可用时留空，不拖垮档案） */
    private void fillUserBrief(CodingAbilityProfileVO vo, Integer userId) {
        try {
            ResponseResult res = userClient.getPublicInfo(userId.longValue());
            if (res != null && res.getCode() != null && res.getCode() == 200
                && res.getData() instanceof Map<?, ?> data) {
                vo.setNickname(data.get("nickname") == null ? "" : String.valueOf(data.get("nickname")));
                vo.setAvatar(data.get("avatar") == null ? "" : String.valueOf(data.get("avatar")));
            }
        } catch (Exception e) {
            log.warn("加载档案用户信息失败, userId={}", userId, e);
        }
    }

    /** 解析 tag_stats（{"Redis":{"total":3,"correct":2}}）→ 按答题量降序前 N */
    private List<CodingAbilityProfileVO.DomainItem> parseDomainItems(String json) {
        List<CodingAbilityProfileVO.DomainItem> items = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return items;
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析答题领域分布失败: {}", json);
            return items;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            CodingAbilityProfileVO.DomainItem item = new CodingAbilityProfileVO.DomainItem();
            item.setTag(entry.getKey());
            item.setTotal(toInt(value.get("total")));
            item.setCorrect(toInt(value.get("correct")));
            items.add(item);
        }
        items.sort(Comparator.comparingInt((CodingAbilityProfileVO.DomainItem item) ->
            item.getTotal() == null ? 0 : item.getTotal()).reversed());
        return items.size() > DOMAIN_TOP_LIMIT
            ? new ArrayList<>(items.subList(0, DOMAIN_TOP_LIMIT)) : items;
    }

    /** 查询最新连续答题天数（签到体系唯一来源，不可用时降级 0） */
    private int currentContinuousDays(Integer userId) {
        try {
            ResponseResult result = rewardClient.getContinuousCheckinDays(userId.longValue());
            if (result != null && result.getData() instanceof Map<?, ?> data
                && data.get("continuousDays") != null) {
                Integer days = toInt(data.get("continuousDays"));
                return days == null ? 0 : days;
            }
        } catch (Exception e) {
            log.warn("获取连续签到天数失败: userId={}", userId, e);
        }
        return 0;
    }

    private static void applyDto(ApCodingProfileSetting setting, CodingProfileSettingDTO dto) {
        if (dto.getIsPublic() != null) {
            setting.setIsPublic(dto.getIsPublic() ? 1 : 0);
        }
        if (dto.getPublicDomain() != null) {
            setting.setPublicDomain(dto.getPublicDomain() ? 1 : 0);
        }
        if (dto.getPublicStreak() != null) {
            setting.setPublicStreak(dto.getPublicStreak() ? 1 : 0);
        }
        if (dto.getPublicOutput() != null) {
            setting.setPublicOutput(dto.getPublicOutput() ? 1 : 0);
        }
        if (dto.getPublicSolve() != null) {
            setting.setPublicSolve(dto.getPublicSolve() ? 1 : 0);
        }
        if (dto.getPublicAssessment() != null) {
            setting.setPublicAssessment(dto.getPublicAssessment() ? 1 : 0);
        }
    }

    private static CodingProfileSettingVO toSettingVO(ApCodingProfileSetting setting) {
        CodingProfileSettingVO vo = new CodingProfileSettingVO();
        vo.setIsPublic(setting != null && isOn(setting.getIsPublic()));
        vo.setPublicDomain(setting == null || setting.domainPublic());
        vo.setPublicStreak(setting == null || setting.streakPublic());
        vo.setPublicOutput(setting == null || setting.outputPublic());
        vo.setPublicSolve(setting != null && isOn(setting.getPublicSolve()));
        vo.setPublicAssessment(setting == null || setting.assessmentPublic());
        return vo;
    }

    private static boolean isOn(Integer value) {
        return value != null && value == 1;
    }

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toDateString(java.util.Date date) {
        if (date == null) {
            return null;
        }
        if (date instanceof Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static String toDateTimeString(java.util.Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}