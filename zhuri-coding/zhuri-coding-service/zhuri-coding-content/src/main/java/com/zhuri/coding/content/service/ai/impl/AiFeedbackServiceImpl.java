package com.zhuri.coding.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.ai.AiFeedbackMapper;
import com.zhuri.coding.content.service.ai.AiFeedbackService;
import com.zhuri.coding.model.ai.pojos.AiFeedback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 反馈实现（👍/👎）
 */
@Slf4j
@Service
public class AiFeedbackServiceImpl implements AiFeedbackService {

    private static final int MAX_TEXT = 500;

    @Autowired
    private AiFeedbackMapper feedbackMapper;

    /** 消费漏斗计（反馈阶段打点：feedback_up / feedback_down） */
    @Autowired
    private com.zhuri.coding.content.service.ai.AiFunnelMeter funnelMeter;

    @Override
    public ResponseResult record(Integer userId, String feature, String sceneId,
                                 String question, String answer, Integer feedback) {
        if (userId == null || feature == null || feedback == null
            || (feedback != AiFeedback.FEEDBACK_UP && feedback != AiFeedback.FEEDBACK_DOWN)) {
            return ResponseResult.errorResult(com.zhuri.coding.model.common.enums.AppHttpCodeEnum.PARAM_INVALID);
        }
        try {
            String q = question == null ? "" : question.trim();
            if (q.length() > MAX_TEXT) {
                q = q.substring(0, MAX_TEXT);
            }
            String a = answer == null ? "" : answer.trim();
            if (a.length() > MAX_TEXT) {
                a = a.substring(0, MAX_TEXT);
            }
            String scene = sceneId == null ? "" : sceneId;
            String hash = md5(q);

            AiFeedback existing = feedbackMapper.selectOne(new LambdaQueryWrapper<AiFeedback>()
                .eq(AiFeedback::getUserId, userId)
                .eq(AiFeedback::getFeature, feature)
                .eq(AiFeedback::getSceneId, scene)
                .eq(AiFeedback::getQuestionHash, hash)
                .last("LIMIT 1"));
            if (existing != null) {
                existing.setFeedback(feedback);
                existing.setAnswer(a.isEmpty() ? existing.getAnswer() : a);
                existing.setUpdateTime(new Date());
                feedbackMapper.updateById(existing);
            } else {
                AiFeedback fb = new AiFeedback();
                fb.setUserId(userId);
                fb.setFeature(feature);
                fb.setSceneId(scene);
                fb.setQuestionHash(hash);
                fb.setQuestion(q);
                fb.setAnswer(a);
                fb.setFeedback(feedback);
                fb.setCreateTime(new Date());
                fb.setUpdateTime(new Date());
                feedbackMapper.insert(fb);
            }
            log.info("AI 反馈已记录, userId={}, feature={}, scene={}, feedback={}", userId, feature, scene, feedback);
            // 消费漏斗：反馈阶段（feature 映射到 AiFeatures 口径，未识别归 OTHER）
            funnelMeter.incr(mapFunnelFeature(feature),
                feedback == AiFeedback.FEEDBACK_UP
                        ? com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_FEEDBACK_UP
                        : com.zhuri.coding.content.service.ai.AiFunnelMeter.STAGE_FEEDBACK_DOWN);
            Map<String, Object> data = new HashMap<>();
            data.put("feedback", feedback);
            return ResponseResult.okResult(data);
        } catch (Exception e) {
            log.warn("AI 反馈记录异常, userId={}", userId, e);
            return ResponseResult.errorResult(503, "反馈记录失败");
        }
    }

    /**
     * 反馈 feature（AiFeedback.FEATURE_*）→ 漏斗口径（AiFeatures）映射。
     * 反馈侧与漏斗侧的常量名不一致（aiask_global vs ask），不映射则 generatedToFeedback 恒为 0。
     */
    private static String mapFunnelFeature(String feature) {
        if (AiFeedback.FEATURE_AIASK_GLOBAL.equals(feature)) {
            return com.zhuri.coding.content.service.ai.AiFeatures.ASK;
        }
        if (AiFeedback.FEATURE_AIASK_ARTICLE.equals(feature)) {
            return com.zhuri.coding.content.service.ai.AiFeatures.ASK_ARTICLE;
        }
        return com.zhuri.coding.content.service.ai.AiFeatures.OTHER;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    // ==================== 反馈回灌（P1-2：闭环的后半段） ====================

    /** 差评率告警阈值：超过即告警（可配置 ai.feedback.alert-down-rate） */
    @org.springframework.beans.factory.annotation.Value("${ai.feedback.alert-down-rate:0.2}")
    private double alertDownRate;

    /** 统计的最小样本量：样本不足时不告警（1 条 👎 就告警会永远在响） */
    private static final int ALERT_MIN_SAMPLES = 5;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiMetricsCollector metrics;

    @Override
    public List<AiFeedback> badCases(String feature, int limit) {
        int max = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<AiFeedback> query = new LambdaQueryWrapper<AiFeedback>()
            .eq(AiFeedback::getFeedback, AiFeedback.FEEDBACK_DOWN)
            .orderByDesc(AiFeedback::getUpdateTime)
            .last("LIMIT " + max);
        if (feature != null && !feature.isBlank()) {
            query.eq(AiFeedback::getFeature, feature);
        }
        List<AiFeedback> list = feedbackMapper.selectList(query);
        return list == null ? java.util.Collections.emptyList() : list;
    }

    @Override
    public List<Map<String, Object>> exportEvalCandidates(String feature, int limit) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (AiFeedback f : badCases(feature, limit)) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("question", f.getQuestion());
            // goldenArticleIds 留空：机器不知道"正确的答案"，人工补上期望召回的文章后即可并入评测集
            item.put("goldenArticleIds", new java.util.ArrayList<>());
            item.put("note", "来自用户差评 feature=" + f.getFeature()
                + " scene=" + (f.getSceneId() == null ? "" : f.getSceneId())
                + " time=" + (f.getUpdateTime() == null ? "" : cn.hutool.core.date.DateUtil.formatDateTime(f.getUpdateTime())));
            out.add(item);
        }
        return out;
    }

    @Override
    public Map<String, Object> statsByFeature(int days) {
        int span = Math.max(1, Math.min(days, 30));
        java.util.Date since = new java.util.Date(System.currentTimeMillis() - span * 86_400_000L);
        List<AiFeedback> all = feedbackMapper.selectList(new LambdaQueryWrapper<AiFeedback>()
            .ge(AiFeedback::getUpdateTime, since));

        // 按 feature 聚合 up/down
        Map<String, long[]> byFeature = new java.util.TreeMap<>();
        for (AiFeedback f : all) {
            if (f.getFeature() == null) {
                continue;
            }
            long[] pair = byFeature.computeIfAbsent(f.getFeature(), k -> new long[2]);
            if (Integer.valueOf(AiFeedback.FEEDBACK_UP).equals(f.getFeedback())) {
                pair[0]++;
            } else if (Integer.valueOf(AiFeedback.FEEDBACK_DOWN).equals(f.getFeedback())) {
                pair[1]++;
            }
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("days", span);
        out.put("alertDownRate", alertDownRate);
        out.put("alertMinSamples", ALERT_MIN_SAMPLES);
        java.util.List<String> alerted = new java.util.ArrayList<>();
        Map<String, Object> byFeatureOut = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : byFeature.entrySet()) {
            long up = e.getValue()[0];
            long down = e.getValue()[1];
            long total = up + down;
            double downRate = total == 0 ? 0d : (double) down / total;
            boolean alert = total >= ALERT_MIN_SAMPLES && downRate >= alertDownRate;
            if (alert) {
                alerted.add(e.getKey());
                metrics.incr("ai_feedback_alert_" + e.getKey());
                log.warn("[AiFeedback] 差评率超阈值，建议复盘/补评测集: feature={}, 👎率={}%, 样本={}",
                        e.getKey(), Math.round(downRate * 100), total);
            }
            Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("up", up);
            s.put("down", down);
            s.put("total", total);
            s.put("downRate", Math.round(downRate * 1000) / 10.0);
            s.put("alert", alert);
            byFeatureOut.put(e.getKey(), s);
        }
        out.put("byFeature", byFeatureOut);
        out.put("alerted", alerted);
        return out;
    }
}
