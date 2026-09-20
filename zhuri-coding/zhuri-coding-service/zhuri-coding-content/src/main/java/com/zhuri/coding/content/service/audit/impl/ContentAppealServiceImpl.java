package com.zhuri.coding.content.service.audit.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.article.ApArticleContentMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.audit.ApContentAppealMapper;
import com.zhuri.coding.content.mapper.comment.ApCommentMapper;
import com.zhuri.coding.content.service.audit.ContentAppealService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticleContent;
import com.zhuri.coding.model.audit.pojos.ApContentAppeal;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 内容治理申诉实现
 */
@Slf4j
@Service
public class ContentAppealServiceImpl implements ContentAppealService {

    private static final int MAX_REASON = 500;
    private static final int REVIEW_CHARS = 4000;
    private static final int AI_VERDICT_MAX = 1000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ApContentAppealMapper appealMapper;

    @Autowired
    private ApCommentMapper apCommentMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApArticleContentMapper contentMapper;

    /** 统一 LLM 出口（安全横切 + token 计量） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;

    @Autowired
    @Qualifier("aiSseExecutor")
    private Executor aiSseExecutor;

    @Override
    public ResponseResult submit(Integer appealType, Long contentId, String reason, Integer applicantId) {
        if (appealType == null || contentId == null || applicantId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        String r = reason == null ? "" : reason.trim();
        if (r.length() > MAX_REASON) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "申诉理由不能超过" + MAX_REASON + "字");
        }
        // 归属校验：申诉人须为内容归属者
        if (!isOwner(appealType, contentId, applicantId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "只能申诉自己发布的内容");
        }
        // 幂等：同对象存在处理中申诉则拒绝
        Long pending = appealMapper.selectCount(new LambdaQueryWrapper<ApContentAppeal>()
            .eq(ApContentAppeal::getAppealType, appealType)
            .eq(ApContentAppeal::getContentId, contentId)
            .eq(ApContentAppeal::getStatus, ApContentAppeal.STATUS_PENDING));
        if (pending != null && pending > 0) {
            return ResponseResult.errorResult(500, "已有处理中的申诉，请耐心等待");
        }

        ApContentAppeal appeal = new ApContentAppeal();
        appeal.setAppealType(appealType);
        appeal.setContentId(contentId);
        appeal.setApplicantId(applicantId);
        appeal.setReason(r);
        appeal.setAiVerdict("");
        appeal.setStatus(ApContentAppeal.STATUS_PENDING);
        appeal.setCreateTime(new Date());
        appeal.setUpdateTime(new Date());
        appealMapper.insert(appeal);

        // AI 预审（异步，不阻塞提交）
        Long appealId = appeal.getId();
        try {
            CompletableFuture.runAsync(() -> aiPreReview(appealId), aiSseExecutor);
        } catch (Exception e) {
            log.warn("AI 预审投递失败, appealId={}", appealId, e);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("appealId", appealId);
        data.put("status", ApContentAppeal.STATUS_PENDING);
        log.info("内容申诉已提交, type={}, contentId={}, applicantId={}", appealType, contentId, applicantId);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult review(Long appealId, String action, Integer reviewerId) {
        if (appealId == null || action == null || reviewerId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        ApContentAppeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "申诉不存在");
        }
        if (appeal.getStatus() != ApContentAppeal.STATUS_PENDING) {
            return ResponseResult.errorResult(500, "该申诉已终审");
        }
        // 防自审：申诉人不能终审自己的申诉（生产需接入运营角色权限体系）
        if (reviewerId.equals(appeal.getApplicantId())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NO_OPERATOR_AUTH, "申诉人不能终审自己的申诉");
        }
        boolean allow = "allow".equalsIgnoreCase(action);
        if (!allow && !"uphold".equalsIgnoreCase(action)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "action 仅支持 allow/uphold");
        }

        if (allow) {
            revertDisposal(appeal.getAppealType(), appeal.getContentId());
            appeal.setStatus(ApContentAppeal.STATUS_ALLOWED);
        } else {
            appeal.setStatus(ApContentAppeal.STATUS_REJECTED);
        }
        appeal.setUpdateTime(new Date());
        appealMapper.updateById(appeal);

        Map<String, Object> data = new HashMap<>();
        data.put("appealId", appeal.getId());
        data.put("status", appeal.getStatus());
        log.info("申诉已终审, appealId={}, action={}, reviewerId={}", appealId, action, reviewerId);
        return ResponseResult.okResult(data);
    }

    @Override
    public ResponseResult getStatus(Integer appealType, Long contentId, Integer userId) {
        if (appealType == null || contentId == null || userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        ApContentAppeal appeal = appealMapper.selectOne(new LambdaQueryWrapper<ApContentAppeal>()
            .eq(ApContentAppeal::getAppealType, appealType)
            .eq(ApContentAppeal::getContentId, contentId)
            .eq(ApContentAppeal::getApplicantId, userId)
            .orderByDesc(ApContentAppeal::getId)
            .last("LIMIT 1"));
        Map<String, Object> data = new HashMap<>();
        if (appeal == null) {
            data.put("status", -1); // 无申诉记录
            data.put("aiVerdict", new HashMap<>());
            data.put("reason", "");
            return ResponseResult.okResult(data);
        }
        data.put("status", appeal.getStatus());
        data.put("reason", appeal.getReason() != null ? appeal.getReason() : "");
        data.put("aiVerdict", parseVerdict(appeal.getAiVerdict()));
        return ResponseResult.okResult(data);
    }

    // ==================== 内部方法 ====================

    /** 归属校验：type=1 评论作者；type=2 文章作者 */
    private boolean isOwner(int appealType, Long contentId, Integer userId) {
        try {
            if (appealType == ApContentAppeal.TYPE_COMMENT_HIDDEN) {
                ApComment c = apCommentMapper.selectById(contentId);
                return c != null && c.getUserId() != null && c.getUserId().equals(userId);
            }
            if (appealType == ApContentAppeal.TYPE_ARTICLE_AIGC) {
                ApArticle a = apArticleMapper.selectById(contentId);
                return a != null && a.getAuthorId() != null && a.getAuthorId().equals(userId.longValue());
            }
            return false;
        } catch (Exception e) {
            log.warn("申诉归属校验异常, type={}, contentId={}", appealType, contentId, e);
            return false;
        }
    }

    /** AI 预审：给建议不终决 */
    private void aiPreReview(Long appealId) {
        ApContentAppeal appeal = appealMapper.selectById(appealId);
        if (appeal == null || appeal.getStatus() != ApContentAppeal.STATUS_PENDING) {
            return;
        }
        try {
            String source = null;
            String reason = appeal.getReason() != null ? appeal.getReason() : "";
            String sys;
            String user;
            if (appeal.getAppealType() == ApContentAppeal.TYPE_COMMENT_HIDDEN) {
                ApComment c = apCommentMapper.selectById(appeal.getContentId());
                if (c == null) {
                    return;
                }
                source = c.getContent();
                sys = "你是社区治理申诉复核员。一条评论此前因疑似引战/阴阳怪气/软广被 AI 折叠（仅作者本人可见）。"
                    + "请基于评论原文与申诉理由复核是否误伤：正常的不同意见、批评、调侃、疑问不算引战；"
                    + "确实含人身攻击/明显挑衅/广告引流/刷屏才应维持折叠。只输出 JSON："
                    + "{\"suggest\":\"allow\"|\"uphold\",\"score\":0-100,\"reason\":\"不超过40字\"}（allow=建议解除折叠，uphold=维持折叠）";
                user = "评论原文：" + (source == null ? "" : source)
                    + "\n申诉理由：" + reason;
            } else {
                ApArticle a = apArticleMapper.selectById(appeal.getContentId());
                ApArticleContent ac = a == null ? null : contentMapper.selectOne(
                    new LambdaQueryWrapper<ApArticleContent>().eq(ApArticleContent::getArticleId, a.getId()).last("LIMIT 1"));
                String body = (ac != null && ac.getContent() != null) ? ac.getContent() : "";
                source = body.length() > REVIEW_CHARS ? body.substring(0, REVIEW_CHARS) : body;
                sys = "你是内容诚信申诉复核员。一篇文章此前被判定疑似 AI 批量生成的水文（已关闭打赏/不入向量库）。"
                    + "请基于文章原文与申诉理由复核是否误伤：含个人经验、具体案例、真实项目细节、代码或数据的通常为人工创作；"
                    + "模板化空转、无个人痕迹、车轱辘话更可能为 AI 水文。只输出 JSON："
                    + "{\"suggest\":\"allow\"|\"uphold\",\"score\":0-100,\"reason\":\"不超过40字\"}（allow=建议解除误标，uphold=维持标注）";
                user = "申诉理由：" + reason + "\n\n文章原文：\n" + source;
            }
            String raw = llmGateway.generateOrNull(com.zhuri.coding.content.service.ai.AiFeatures.APPEAL_AUDIT,
                sys, user, null, null);
            String verdict = normalizeVerdict(raw);
            if (verdict != null) {
                appeal.setAiVerdict(verdict);
                appeal.setUpdateTime(new Date());
                appealMapper.updateById(appeal);
                log.info("申诉 AI 预审完成, appealId={}, verdict={}", appealId, verdict);
            }
        } catch (Exception e) {
            log.warn("申诉 AI 预审异常, appealId={}", appealId, e);
        }
    }

    /** 抽取模型输出中的 JSON verdict（容错围栏/杂文本），超长截断 */
    private String normalizeVerdict(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int s = raw.indexOf('{');
            int e = raw.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return null;
            }
            String json = raw.substring(s, e + 1);
            if (json.length() > AI_VERDICT_MAX) {
                json = json.substring(0, AI_VERDICT_MAX);
            }
            return json;
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseVerdict(String aiVerdict) {
        if (aiVerdict == null || aiVerdict.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(aiVerdict, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** 终审 allow：解除原处置（评论折叠清 is_hidden；文章 AIGC 清 is_aigc/aigc_score） */
    private void revertDisposal(int appealType, Long contentId) {
        try {
            if (appealType == ApContentAppeal.TYPE_COMMENT_HIDDEN) {
                ApComment c = apCommentMapper.selectById(contentId);
                if (c != null && c.getIsHidden() != null && c.getIsHidden() == 1) {
                    apCommentMapper.update(null, new LambdaUpdateWrapper<ApComment>()
                        .eq(ApComment::getId, contentId).set(ApComment::getIsHidden, 0));
                    log.info("申诉解除评论折叠, commentId={}", contentId);
                }
            } else if (appealType == ApContentAppeal.TYPE_ARTICLE_AIGC) {
                apArticleMapper.update(null, new LambdaUpdateWrapper<ApArticle>()
                    .eq(ApArticle::getId, contentId)
                    .set(ApArticle::getIsAigc, 0)
                    .set(ApArticle::getAigcScore, 0));
                log.info("申诉解除文章 AIGC 标注, articleId={}", contentId);
            }
        } catch (Exception e) {
            log.error("申诉解除处置异常, type={}, contentId={}", appealType, contentId, e);
        }
    }
}
