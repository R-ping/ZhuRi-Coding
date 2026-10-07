package com.zhuri.coding.content.controller.v1.admin;

import com.zhuri.coding.common.admin.RequireAdminPermission;
import com.zhuri.coding.content.service.admin.AdminActivityService;
import com.zhuri.coding.model.admin.AdminPermission;
import com.zhuri.coding.model.admin.dtos.AdminActionDto;
import com.zhuri.coding.model.admin.dtos.AdminActivitySaveDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 活动管理（CMS）。
 *
 * <p>在做这个控制器之前，{@code ap_activity} 只有两个只读接口 —— 表里每一条活动都是
 * 手写 SQL 造出来的。这里补齐的是「建档 → 编辑 → 上线 → 下线」整条链路，
 * 以及"建错了的草稿要能删掉"这个收尾动作。
 *
 * <p><b>为什么写操作全是 POST，只有编辑用 PUT</b>：与 {@code AdminOpsConfigController}
 * 的说明同源 —— PUT 表达"把这份内容变成这样"（提交两次结果相同），POST 表达
 * "再做一次这个动作"。上线、下线、删除都是动作：第二次调用应当被状态闸口拒掉，
 * 而不是静默成功。编辑之所以是 PUT，是因为它提交的是"活动最终应该长什么样"，
 * 重复提交同一份内容结果一致（第二次会被字段闸口拦下并明确告知"与当前一致"）。
 *
 * <p><b>删除的理由走查询参数而不是请求体</b>：HTTP 规范没有禁止 DELETE 携带请求体，
 * 但现实中有中间件会把它丢掉 —— 而这类"参数不见了"的故障最难定位（前端说发了，
 * 后端说没收到）。理由不是敏感信息（它本来就会进审计库），放 URL 里没有额外风险，
 * 换来的是少一个会静默失效的传输路径。
 *
 * <p><b>校验分工</b>：这里只管"这个请求合不合规矩"（标题填了没、日期填了没、理由有没有、
 * 长度超没超），"这份数据能不能写进库"（类型/分类是不是白名单里的、日期先后、
 * 关联话题是否存在、当前状态能不能上线）由 {@code AdminActivityService} 决定 ——
 * 后者对任何调用方都成立，不该只在这里拦一道。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/activities")
public class AdminActivityController {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    @Autowired
    private AdminActivityService adminActivityService;

    // ==================== 读 ====================

    /**
     * 活动列表（含草稿与已下线）。
     * GET /api/v1/admin/activities?status=draft&keyword=征文&page=1&size=20
     *
     * @param status 状态编码：{@code draft/upcoming/ongoing/ended/offline}；不传表示全部。
     *               取值非法时返回参数错误而不是空列表 —— 见服务层注释
     */
    @GetMapping
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult list(@RequestParam(value = "keyword", required = false) String keyword,
                               @RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "type", required = false) String type,
                               @RequestParam(value = "category", required = false) String category,
                               @RequestParam(value = "page", defaultValue = "1") Integer page,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return adminActivityService.page(keyword, status, type, category, page, size);
    }

    /**
     * 活动详情（含草稿与已下线）。
     * GET /api/v1/admin/activities/{id}
     */
    @GetMapping("/{id}")
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult detail(@PathVariable("id") Long id) {
        return adminActivityService.detail(id);
    }

    // ==================== 写 ====================

    /**
     * 新建活动（一律先落草稿）。
     * POST /api/v1/admin/activities
     *
     * <p>想一步到位对外发布的话，建完再调一次上线接口 —— 拆成两步是为了让"何时对外可见"
     * 有一个独立的时间点与独立的理由，而不是混在"建档"这一条审计里。
     */
    @PostMapping
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult create(@RequestBody AdminActivitySaveDto dto) {
        ResponseResult invalid = validateSave(dto);
        if (invalid != null) {
            return invalid;
        }
        return adminActivityService.create(dto);
    }

    /**
     * 编辑活动。
     * PUT /api/v1/admin/activities/{id}
     *
     * <p>已发布的活动改了日期，阶段会按新日期重算（活动延期就是这么做的）。
     */
    @PutMapping("/{id}")
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult update(@PathVariable("id") Long id, @RequestBody AdminActivitySaveDto dto) {
        ResponseResult invalid = validateSave(dto);
        if (invalid != null) {
            return invalid;
        }
        return adminActivityService.update(id, dto);
    }

    /**
     * 上线（草稿 / 已下线 → 按日期定阶段）。
     * POST /api/v1/admin/activities/{id}/publish
     */
    @PostMapping("/{id}/publish")
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult publish(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminActivityService.publish(id, dto.getReason().trim());
    }

    /**
     * 下线（已发布 → 已下线）。只改可见性，不删数据。
     * POST /api/v1/admin/activities/{id}/offline
     */
    @PostMapping("/{id}/offline")
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult offline(@PathVariable("id") Long id, @RequestBody AdminActionDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        return adminActivityService.offline(id, dto.getReason().trim());
    }

    /**
     * 删除活动（仅限草稿）。已发布的活动请用下线。
     * DELETE /api/v1/admin/activities/{id}?reason=xxx
     */
    @DeleteMapping("/{id}")
    @RequireAdminPermission(AdminPermission.ACTIVITY_MANAGE)
    public ResponseResult delete(@PathVariable("id") Long id,
                                 @RequestParam("reason") String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        return adminActivityService.delete(id, reason.trim());
    }

    // ==================== 校验 ====================

    /**
     * 新建 / 编辑共用的入参校验，返回 null 表示通过。
     *
     * <p>只检查"字段在不在"。类型是不是白名单里的、日期格式对不对、结束日期有没有早于开始日期、
     * 关联的话题存不存在 —— 这些都要读库或查表，属于服务层的判断，放在这里会变成两处各写一遍。
     */
    private ResponseResult validateSave(AdminActivitySaveDto dto) {
        if (dto == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
        }
        ResponseResult reasonInvalid = validateReason(dto.getReason());
        if (reasonInvalid != null) {
            return reasonInvalid;
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写活动标题");
        }
        if (dto.getStartDate() == null || dto.getStartDate().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写活动开始日期");
        }
        if (dto.getEndDate() == null || dto.getEndDate().isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写活动结束日期");
        }
        return null;
    }

    /**
     * 理由必须非空：上线决定"全站用户能不能看到这条活动"，下线决定"什么时候撤的"。
     * 事后追问只能靠这句话回答。
     */
    private ResponseResult validateReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写操作理由");
        }
        if (value.length() > REASON_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "操作理由不能超过" + REASON_MAX_LEN + "字");
        }
        return null;
    }
}
