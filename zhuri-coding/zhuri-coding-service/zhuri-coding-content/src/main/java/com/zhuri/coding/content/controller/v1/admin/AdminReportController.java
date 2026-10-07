package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminReportService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.ReportHandleDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 举报处置。
 *
 * <p>这是「举报」这个功能第一次有了出口 —— 在此之前 {@code ap_article_report} 只有写入没有读取，
 * 用户提交的举报没有任何人会看到。
 *
 * <p><b>两个接口的权限为什么不同</b>：查看队列（{@code REPORT_VIEW}）与处置（{@code REPORT_HANDLE}）
 * 分开，是因为"能看"和"能改"在治理岗位上往往不是同一批人（例如实习生只看、正式审核员才判）。
 * 而"下架内容"还要再叠一层 {@code CONTENT_TAKE_DOWN}：驳回与警告是可逆的判断，
 * 把已发布内容从线上撤下来会直接影响作者与读者，属于更重的动作，单独授权。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    /** 处置说明上限，与 {@code ap_admin_audit_log.reason} / {@code ap_article_report.handle_reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminReportService adminReportService;

    /**
     * 举报队列分页。
     * GET /api/v1/admin/reports?status=0&page=1&size=20
     *
     * @param status 处理状态过滤（0待处理 1已处理）；不传表示全部
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.REPORT_VIEW)
    public ResponseResult list(@RequestParam(value = "status", required = false) Integer status,
                               @RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminReportService.page(status, page, size);
    }

    /**
     * 处置一条举报。
     * POST /api/v1/admin/reports/{id}/handle
     *
     * <p>处置会连带结清同一篇文章的其余待处理举报，并为每一条的举报人各发一份回执。
     */
    @PostMapping("/{id}/handle")
    @RequireAdminPermission(AdminPermission.REPORT_HANDLE)
    public ResponseResult handle(@PathVariable("id") Long id, @RequestBody ReportHandleDto dto) {
        if (id == null || dto == null || dto.getHandleResult() == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不完整");
        }
        if (!ReportHandleDto.ALLOWED_RESULTS.contains(dto.getHandleResult())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的处置结论");
        }
        // 理由在服务端再校验一次：前端校验只防手误，不防伪造请求。
        // 它不只是留痕字段 —— 会原样回执给举报人，所以空话（如"已处理"）在这里没有意义，
        // 但"必须写"这件事只能靠长度下限与人的自觉，代码拦不住敷衍。
        String reason = dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写处置说明");
        }
        if (reason.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "处置说明不能超过" + REASON_MAX_LEN + "字");
        }
        // 「下架」需要叠加 CONTENT_TAKE_DOWN 权限（本注解只保证了 REPORT_HANDLE）。
        // 该判断放在服务层而不是这里：它要写一条"越权尝试"的审计，而审计依赖已经开好的事务上下文，
        // 服务层本来就带着审计记录器，放在这里只会把这段逻辑抄第二遍。
        return adminReportService.handle(id, dto.getHandleResult(), reason);
    }
}
