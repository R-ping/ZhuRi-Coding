package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营侧举报处置。
 *
 * <p><b>闭环的四段</b>：能看到队列 → 能判（标题、正文入口、被举报次数）→ 能办（驳回/警告/下架）→
 * 有回音（回执举报人、告知作者、留审计）。这四段缺任何一段，<em>举报</em> 这个功能对用户就是
 * 一个没有回应的黑洞，用户第一次举报收不到回音就不会有第二次。
 *
 * <p><b>为什么处置方法是"文章级"的</b>：同一篇文章可能被十几个人分别举报，
 * 逐条处置既费人力也容易前后结论不一致。结论一旦作出，该文章下所有待处理举报一次性结案，
 * 每一条的举报人各收一份回执 —— 结案粒度是文章，回执粒度是举报人。
 */
public interface AdminReportService {

    /** 审计动作：驳回举报 */
    String ACTION_REJECT = "REPORT_REJECT";
    /** 审计动作：警告作者 */
    String ACTION_WARN_AUTHOR = "REPORT_WARN_AUTHOR";
    /** 审计动作：下架内容 */
    String ACTION_TAKE_DOWN = "REPORT_TAKE_DOWN";

    /**
     * 举报队列分页。
     *
     * @param status 处理状态过滤（0待处理 1已处理）；null 表示不过滤
     */
    ResponseResult page(Integer status, Integer page, Integer size);

    /**
     * 处置一条举报（连带结清同文章的其余待处理举报）。
     *
     * @param reportId     举报记录ID
     * @param handleResult 处置结论：1驳回 2警告作者 3下架内容
     * @param reason       处置说明（必填，写审计并原样回执给举报人）
     */
    ResponseResult handle(Long reportId, Integer handleResult, String reason);
}
