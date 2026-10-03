package com.zhuri.coding.content.controller.v1.coding;

import com.zhuri.coding.content.service.coding.CodingAssessmentService;
import com.zhuri.coding.model.coding.dtos.CodingAssessmentSubmitDTO;
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
 * 能力测评接口（Coding 延展第二层 · Stage B）
 *
 * <p>路径：/api/v1/coding/assessment（经网关 /content 前缀转发）。全部需登录
 * （以 ThreadLocal 用户为准，不接受外部传 userId）；开卷/交卷含写操作，未开放白名单。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/coding/assessment")
public class CodingAssessmentController {

    @Autowired
    private CodingAssessmentService assessmentService;

    /**
     * 开卷：冷却内拒绝（提示下次可考时间）；有进行中返回续答；否则组卷创建
     * POST /api/v1/coding/assessment/start
     */
    @PostMapping("/start")
    public ResponseResult start() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return assessmentService.start(user.getId());
    }

    /**
     * 进行中的测评（懒过期：已超时置过期并返回空）
     * GET /api/v1/coding/assessment/current
     */
    @GetMapping("/current")
    public ResponseResult current() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return assessmentService.current(user.getId());
    }

    /**
     * 交卷判分：幂等（重复交卷返回同一成绩单）；超时拒绝
     * POST /api/v1/coding/assessment/submit
     * {"assessmentId": 1, "answers": [{"questionId": 1, "userAnswer": [0]}]}
     */
    @PostMapping("/submit")
    public ResponseResult submit(@RequestBody CodingAssessmentSubmitDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return assessmentService.submit(user.getId(), dto);
    }

    /**
     * 最近一次已提交成绩单（无记录返回空；档案块/独立页复用）
     * GET /api/v1/coding/assessment/latest
     */
    @GetMapping("/latest")
    public ResponseResult latest() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return assessmentService.latest(user.getId());
    }

    /**
     * 历史列表（分页，含进行中/已过期状态）
     * GET /api/v1/coding/assessment/history?page=1&size=10
     */
    @GetMapping("/history")
    public ResponseResult history(@RequestParam(value = "page", defaultValue = "1") Integer page,
                                  @RequestParam(value = "size", defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return assessmentService.history(user.getId(), page, size);
    }
}