package com.zhuri.coding.content.controller.v1.coding;

import com.zhuri.coding.content.service.coding.CodingQuestionService;
import com.zhuri.coding.content.service.coding.CodingSupplyService;
import com.zhuri.coding.model.coding.dtos.CodingAnswerDTO;
import com.zhuri.coding.model.coding.dtos.CodingQuestionSubmitDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日一题与刷题接口（Coding 延展第一层）
 *
 * <p>路径：/api/v1/coding（经网关 /content 前缀转发）。</p>
 *
 * <p>登录口径：今日题/作答/统计/出题需要登录（以 ThreadLocal 用户为准，不接受外部传 userId）；
 * 榜单与题库列表公开只读（未登录可浏览，登录时标记 isSelf/已答）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/coding")
public class CodingDailyController {

    @Autowired
    private CodingQuestionService questionService;

    @Autowired
    private CodingSupplyService supplyService;

    /**
     * 今日题目：当天已答回放结果，未答按自选/自适应难度抽题
     * GET /api/v1/coding/today?difficulty=2
     */
    @GetMapping("/today")
    public ResponseResult today(@RequestParam(value = "difficulty", required = false) Integer difficulty) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return questionService.today(user.getId(), difficulty);
    }

    /**
     * 提交作答：判分并回放答案与解析
     * POST /api/v1/coding/answer
     * {"questionId": 1, "answers": [0], "elapsedSeconds": 12, "isDaily": true}
     */
    @PostMapping("/answer")
    public ResponseResult answer(@RequestBody CodingAnswerDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return questionService.answer(user.getId(), dto);
    }

    /**
     * 榜单（公开只读）：day 当日 / week 本周 / month 本月
     * GET /api/v1/coding/ranking?period=day
     */
    @GetMapping("/ranking")
    public ResponseResult ranking(@RequestParam(value = "period", defaultValue = "day") String period) {
        ApUser user = AppThreadLocalUtil.getUser();
        return questionService.ranking(period, user == null ? null : user.getId());
    }

    /**
     * 题库列表（公开只读，不含答案；登录时标记已答）：练习入口，
     * 支持按难度与来源文章过滤（文章详情页"相关练习"反向入口）
     * GET /api/v1/coding/questions?difficulty=1&articleId=123&page=1&size=10
     */
    @GetMapping("/questions")
    public ResponseResult questions(@RequestParam(value = "difficulty", required = false) Integer difficulty,
                                    @RequestParam(value = "articleId", required = false) Long articleId,
                                    @RequestParam(value = "page", defaultValue = "1") Integer page,
                                    @RequestParam(value = "size", defaultValue = "10") Integer size) {
        ApUser user = AppThreadLocalUtil.getUser();
        return questionService.questions(difficulty, articleId, page, size,
            user == null ? null : user.getId());
    }

    /**
     * 我的编码统计：连续天数（签到体系）+ 作答正确率 + 领域分布
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

    /**
     * AI 从文章生成题目（仅文章作者可触发，每日有次数上限）
     * POST /api/v1/coding/question/generate {"articleId": 123}
     */
    @PostMapping("/question/generate")
    public ResponseResult generate(@RequestBody Map<String, Object> params) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Long articleId = null;
        Object raw = params == null ? null : params.get("articleId");
        if (raw != null) {
            try {
                articleId = Long.valueOf(String.valueOf(raw));
            } catch (NumberFormatException e) {
                return ResponseResult.errorResult(400, "文章ID格式不正确");
            }
        }
        return supplyService.generateFromArticle(user.getId(), articleId);
    }

    /**
     * 作者投稿题目：格式校验 + 查重 + 一次 AI 质检
     * POST /api/v1/coding/question/submit
     */
    @PostMapping("/question/submit")
    public ResponseResult submit(@RequestBody CodingQuestionSubmitDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return supplyService.submitQuestion(user.getId(), dto);
    }
}