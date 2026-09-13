package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.ai.AiFeedbackMapper;
import com.heima.content.service.ai.AiFeedbackService;
import com.heima.model.ai.pojos.AiFeedback;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
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

    @Override
    public ResponseResult record(Integer userId, String feature, String sceneId,
                                 String question, String answer, Integer feedback) {
        if (userId == null || feature == null || feedback == null
            || (feedback != AiFeedback.FEEDBACK_UP && feedback != AiFeedback.FEEDBACK_DOWN)) {
            return ResponseResult.errorResult(com.heima.model.common.enums.AppHttpCodeEnum.PARAM_INVALID);
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
            Map<String, Object> data = new HashMap<>();
            data.put("feedback", feedback);
            return ResponseResult.okResult(data);
        } catch (Exception e) {
            log.warn("AI 反馈记录异常, userId={}", userId, e);
            return ResponseResult.errorResult(503, "反馈记录失败");
        }
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
}
