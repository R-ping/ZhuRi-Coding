package com.zhuri.coding.content.controller.v1.coding;

import com.zhuri.coding.content.service.coding.CodingQuestionService;
import com.zhuri.coding.model.coding.dtos.CodingDailyAnswerDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日一题接口（Coding 延展第一层 · 简答）
 *
 * <p>路径：/api/v1/coding（经网关 /content 前缀转发）。</p>
 *
 * <p>登录口径：三个接口都需要登录（以 ThreadLocal 用户为准，不接受外部传 userId）——
 * 今日题带个性化方向、作答是写操作、统计只属于本人。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/coding")
public class CodingDailyController {

    @Autowired
    private CodingQuestionService questionService;

    /**
     * 今日题目：当天已答回放完整结果（含等级与考点清单），未答按方向抽题
     * GET /api/v1/coding/today?direction=Java%20后端
     */
    @GetMapping("/today")
    public ResponseResult today(@RequestParam(value = "direction", required = false) String direction) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return questionService.today(user.getId(), direction);
    }

    /**
     * 提交作答（简答）：评估并回放等级、考点覆盖与点评
     * POST /api/v1/coding/answer
     * {"poolId": 1, "answerText": "...", "elapsedSeconds": 180}
     */
    @PostMapping("/answer")
    public ResponseResult answer(@RequestBody CodingDailyAnswerDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return questionService.answer(user.getId(), dto);
    }

    /**
     * 我的编码统计：连续签到天数 + 累计作答/平均等级/领域分布 + 今日状态 + 当前方向
     * GET /api/v1/coding/stat
     */
    @GetMapping("/stat")
    public ResponseResult stat() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return questionService.myStat(user.getId());
    }
}
