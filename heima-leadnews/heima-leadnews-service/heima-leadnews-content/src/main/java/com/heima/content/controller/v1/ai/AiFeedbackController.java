package com.heima.content.controller.v1.ai;

import com.heima.content.service.ai.AiFeedbackService;
import com.heima.model.ai.pojos.AiFeedback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 反馈端点（👍/👎 反馈闭环）：让问答/摘要/预检的迭代有数据依据
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiFeedbackController {

    @Autowired
    private AiFeedbackService aiFeedbackService;

    @PostMapping("/feedback")
    @com.heima.common.annotation.RateLimit(dimension = com.heima.common.annotation.RateLimit.Dimension.USER,
        count = 30, interval = 1, timeUnit = com.heima.common.annotation.RateLimit.TimeUnit.MINUTES)
    public ResponseResult feedback(@RequestBody Map<String, Object> body) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null || user.getId() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        if (body == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        String feature = body.get("feature") == null ? null : String.valueOf(body.get("feature"));
        String sceneId = body.get("sceneId") == null ? "" : String.valueOf(body.get("sceneId"));
        String question = body.get("question") == null ? "" : String.valueOf(body.get("question"));
        String answer = body.get("answer") == null ? "" : String.valueOf(body.get("answer"));
        Integer feedback = body.get("feedback") instanceof Number
            ? ((Number) body.get("feedback")).intValue() : null;
        return aiFeedbackService.record(user.getId(), feature, sceneId, question, answer, feedback);
    }
}
