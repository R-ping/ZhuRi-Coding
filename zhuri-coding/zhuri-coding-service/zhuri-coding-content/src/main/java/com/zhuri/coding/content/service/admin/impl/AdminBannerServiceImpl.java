package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.ops.ApBannerMapper;
import com.zhuri.coding.content.service.admin.AdminBannerService;
import com.zhuri.coding.model.admin.dtos.AdminBannerSaveDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminBannerVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.ops.pojos.ApBanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Banner 管理实现。
 *
 * <p>接口层面的取舍见 {@link AdminBannerService}；幂等闸口、先读后条件更新、
 * 审计分层与 {@code AdminActivityServiceImpl} 同构，这里只写 Banner 特有的决定。
 *
 * <p><b>linkUrl 协议白名单（安全闸口，不是格式洁癖）</b>：Banner 的跳转地址最终会
 * 变成用户浏览器里的 {@code <a href>} 或路由跳转。放任意字符串的话，
 * {@code javascript:alert(1)} 会原样下发到 C 端 —— 运营粘错内容，受害的是全站用户。
 * 白名单只放行站内路由（{@code /} 开头）与 http(s) 外链，其余一律参数错误。
 *
 * <p><b>时间窗只在 C 端判定，管理端启停不校验</b>：启用一条"结束时间已过"的 Banner
 * 是允许的（C 端看不到而已）—— 拦它反而制造"明明合法却报错"的困惑；
 * 时间窗过没过是展示层的事，启用/停用表达的是运营意图。
 */
@Slf4j
@Service
public class AdminBannerServiceImpl implements AdminBannerService {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;
    /** 审计 detail 摘要上限 */
    private static final int DETAIL_MAX_LEN = 1000;
    private static final int TITLE_MAX_LEN = 100;
    private static final int URL_MAX_LEN = 500;

    /**
     * 入参时刻格式：{@code uuuu-MM-dd HH:mm:ss}，STRICT 解析
     * （{@code yyyy} 是纪元年，STRICT 下要纪元才能解析；SMART 会把 2 月 31 日顺延成 3 月 2 日）。
     */
    private static final DateTimeFormatter PARSE_DATETIME =
        DateTimeFormatter.ofPattern("uuuu-M-d H:m:s").withResolverStyle(ResolverStyle.STRICT);

    @Autowired
    private ApBannerMapper apBannerMapper;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    // ==================== 读 ====================

    @Override
    public ResponseResult page(String keyword, Integer status, Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        LambdaQueryWrapper<ApBanner> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ApBanner::getTitle, keyword.trim());
        }
        if (status != null) {
            // 状态拼错要报错而不是返回空列表：空列表会让运营以为"这个状态确实没数据"
            if (status != ApBanner.STATUS_DISABLED && status != ApBanner.STATUS_ENABLED) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的状态：" + status + "（0-停用 1-启用）");
            }
            wrapper.eq(ApBanner::getStatus, status);
        }
        wrapper.orderByAsc(ApBanner::getSortOrder)
            .orderByDesc(ApBanner::getId);

        IPage<ApBanner> result = apBannerMapper.selectPage(new Page<>(p, s), wrapper);
        List<AdminBannerVO> list = result.getRecords().stream()
            .map(this::toVO)
            .collect(Collectors.toList());
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    @Override
    public ResponseResult detail(Long id) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "BannerID不合法");
        }
        ApBanner banner = apBannerMapper.selectById(id);
        if (banner == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "Banner不存在");
        }
        return ResponseResult.okResult(toVO(banner));
    }

    // ==================== 新建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult create(AdminBannerSaveDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_CREATE, TARGET_BANNER, null, reasonOf(dto));
        try {
            return doCreate(dto, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doCreate(AdminBannerSaveDto dto, ApAdminAuditLog audit) {
        Prepared prepared = prepare(dto);
        if (prepared.error != null) {
            return prepared.error;
        }
        ApBanner entity = new ApBanner();
        applyFields(entity, prepared);
        // 新建一律落停用态：启用是另一个动作、另一条审计（"什么时候开始挂出去"要有独立理由）
        entity.setStatus(ApBanner.STATUS_DISABLED);
        Date now = new Date();
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        apBannerMapper.insert(entity);

        audit.setTargetId(String.valueOf(entity.getId()));
        audit.setDetail(truncate("新建 Banner：" + describeFields(prepared)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminBanner] 新建 Banner id={}, title={}, sortOrder={}",
            entity.getId(), prepared.title, prepared.sortOrder);
        return ResponseResult.okResult(toVO(apBannerMapper.selectById(entity.getId())));
    }

    // ==================== 编辑 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult update(Long id, AdminBannerSaveDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_UPDATE, TARGET_BANNER, id == null ? null : String.valueOf(id), reasonOf(dto));
        try {
            return doUpdate(id, dto, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doUpdate(Long id, AdminBannerSaveDto dto, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "BannerID不合法");
        }
        ApBanner before = apBannerMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "Banner不存在");
        }
        Prepared prepared = prepare(dto);
        if (prepared.error != null) {
            return prepared.error;
        }
        if (!changed(before, prepared)) {
            // 静默成功会让运营以为"我刚改的生效了"，审计里还多一条没有差别的记录
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "信息与当前一致，无需保存");
        }

        apBannerMapper.update(null, updateWrapper(prepared)
            .eq(ApBanner::getId, id));

        audit.setDetail(truncate(describeChange(before, prepared)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminBanner] 编辑 Banner id={}", id);
        return ResponseResult.okResult(toVO(apBannerMapper.selectById(id)));
    }

    // ==================== 启停 / 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult enable(Long id, String reason) {
        return toggleStatus(id, ApBanner.STATUS_ENABLED, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult disable(Long id, String reason) {
        return toggleStatus(id, ApBanner.STATUS_DISABLED, reason);
    }

    /** 启停共用主体：先读（给人话错误）再条件更新（挡并发），动作码按目标状态选 */
    private ResponseResult toggleStatus(Long id, int targetStatus, String reason) {
        String action = targetStatus == ApBanner.STATUS_ENABLED ? ACTION_ENABLE : ACTION_DISABLE;
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            action, TARGET_BANNER, id == null ? null : String.valueOf(id), reason.trim());
        try {
            if (id == null || id <= 0) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "BannerID不合法");
            }
            ApBanner before = apBannerMapper.selectById(id);
            if (before == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "Banner不存在");
            }
            if (before.getStatus() != null && before.getStatus() == targetStatus) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "当前已" + statusDesc(targetStatus) + "，无需"
                        + (targetStatus == ApBanner.STATUS_ENABLED ? "启用" : "停用"));
            }
            int rows = apBannerMapper.update(null, new LambdaUpdateWrapper<ApBanner>()
                .set(ApBanner::getStatus, targetStatus)
                .set(ApBanner::getUpdatedTime, new Date())
                .eq(ApBanner::getId, id)
                .eq(ApBanner::getStatus, before.getStatus()));
            if (rows == 0) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "状态已被其他操作改变，请刷新后重试");
            }
            audit.setDetail(truncate("Banner「" + safe(before.getTitle()) + "」"
                + statusDesc(before.getStatus()) + " -> " + statusDesc(targetStatus)
                + (before.getEndTime() != null && before.getEndTime().before(new Date())
                    ? "（注意：结束时间已过，C 端不可见，如需展示请先编辑时间窗）" : "")));
            auditRecorder.recordSuccess(audit);
            log.info("[AdminBanner] {} Banner id={}", action, id);
            return ResponseResult.okResult(toVO(apBannerMapper.selectById(id)));
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult delete(Long id, String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_DELETE, TARGET_BANNER, id == null ? null : String.valueOf(id), reason.trim());
        try {
            return doDelete(id, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doDelete(Long id, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "BannerID不合法");
        }
        ApBanner before = apBannerMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "Banner不存在");
        }
        if (before.getStatus() != null && before.getStatus() == ApBanner.STATUS_ENABLED) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "启用中的 Banner 不能删除，请先停用（停用后 C 端立即不可见）");
        }
        int rows = apBannerMapper.delete(new LambdaQueryWrapper<ApBanner>()
            .eq(ApBanner::getId, id)
            .eq(ApBanner::getStatus, ApBanner.STATUS_DISABLED));
        if (rows == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "状态已被其他操作改变，请刷新后重试");
        }
        audit.setDetail(truncate("删除停用 Banner：「" + safe(before.getTitle()) + "」"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminBanner] 删除 Banner id={}, title={}", id, before.getTitle());
        return ResponseResult.okResult();
    }

    // ==================== 入参校验与装配 ====================

    private Prepared prepare(AdminBannerSaveDto dto) {
        Prepared p = new Prepared();
        if (dto == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
            return p;
        }
        String title = trimToNull(dto.getTitle());
        if (title == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写备注名");
            return p;
        }
        if (title.length() > TITLE_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "备注名不能超过" + TITLE_MAX_LEN + "字");
            return p;
        }
        String imageUrl = trimToNull(dto.getImageUrl());
        if (imageUrl == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写图片地址");
            return p;
        }
        if (imageUrl.length() > URL_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "图片地址不能超过" + URL_MAX_LEN + "个字符");
            return p;
        }
        String linkUrl = trimToNull(dto.getLinkUrl());
        ResponseResult linkError = validateLink(linkUrl);
        if (linkError != null) {
            p.error = linkError;
            return p;
        }
        Integer sortOrder = dto.getSortOrder() == null ? 0 : dto.getSortOrder();
        if (sortOrder < 0 || sortOrder > SORT_ORDER_MAX) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "展示顺序取值范围 0-" + SORT_ORDER_MAX);
            return p;
        }
        Date startTime = parseDateTime(dto.getStartTime());
        if (dto.getStartTime() != null && !dto.getStartTime().isBlank() && startTime == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "生效开始格式应为 yyyy-MM-dd HH:mm:ss（收到：" + safe(dto.getStartTime()) + "）");
            return p;
        }
        Date endTime = parseDateTime(dto.getEndTime());
        if (dto.getEndTime() != null && !dto.getEndTime().isBlank() && endTime == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "生效结束格式应为 yyyy-MM-dd HH:mm:ss（收到：" + safe(dto.getEndTime()) + "）");
            return p;
        }
        if (startTime != null && endTime != null && !endTime.after(startTime)) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "生效结束不得早于开始");
            return p;
        }

        p.title = title;
        p.imageUrl = imageUrl;
        p.linkUrl = linkUrl;
        p.sortOrder = sortOrder;
        p.startTime = startTime;
        p.endTime = endTime;
        return p;
    }

    /**
     * 跳转协议白名单：站内路由（{@code /} 开头）或 http(s) 外链。
     * 大小写不敏感（{@code JAVASCRIPT:} 也是 javascript:），尾部空白已在前一步 trim。
     */
    private static ResponseResult validateLink(String linkUrl) {
        if (linkUrl == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写跳转地址");
        }
        if (linkUrl.length() > URL_MAX_LEN) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "跳转地址不能超过" + URL_MAX_LEN + "个字符");
        }
        String lower = linkUrl.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/")) {
            return null;
        }
        return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
            "跳转地址只支持站内路由（/ 开头）或 http(s) 外链");
    }

    private static void applyFields(ApBanner entity, Prepared p) {
        entity.setTitle(p.title);
        entity.setImageUrl(p.imageUrl);
        entity.setLinkUrl(p.linkUrl);
        entity.setSortOrder(p.sortOrder);
        entity.setStartTime(p.startTime);
        entity.setEndTime(p.endTime);
    }

    private static LambdaUpdateWrapper<ApBanner> updateWrapper(Prepared p) {
        // 逐列显式 set 而不是 updateById：时间窗"清空"就是要写成 null，updateById 会静默跳过
        return new LambdaUpdateWrapper<ApBanner>()
            .set(ApBanner::getTitle, p.title)
            .set(ApBanner::getImageUrl, p.imageUrl)
            .set(ApBanner::getLinkUrl, p.linkUrl)
            .set(ApBanner::getSortOrder, p.sortOrder)
            .set(ApBanner::getStartTime, p.startTime)
            .set(ApBanner::getEndTime, p.endTime)
            .set(ApBanner::getUpdatedTime, new Date());
    }

    /** 编辑幂等闸口：逐字段比对（统计列不存在，全部都是运营维护的字段） */
    private static boolean changed(ApBanner before, Prepared p) {
        return !Objects.equals(before.getTitle(), p.title)
            || !Objects.equals(before.getImageUrl(), p.imageUrl)
            || !Objects.equals(before.getLinkUrl(), p.linkUrl)
            || !Objects.equals(before.getSortOrder(), p.sortOrder)
            || !Objects.equals(before.getStartTime(), p.startTime)
            || !Objects.equals(before.getEndTime(), p.endTime);
    }

    // ==================== 出参装配 ====================

    private AdminBannerVO toVO(ApBanner b) {
        if (b == null) {
            return null;
        }
        AdminBannerVO vo = new AdminBannerVO();
        vo.setId(b.getId());
        vo.setTitle(b.getTitle());
        vo.setImageUrl(b.getImageUrl());
        vo.setLinkUrl(b.getLinkUrl());
        vo.setSortOrder(b.getSortOrder() == null ? 0 : b.getSortOrder());
        vo.setStatus(b.getStatus());
        vo.setStatusDesc(statusDesc(b.getStatus()));
        vo.setStartTime(b.getStartTime());
        vo.setEndTime(b.getEndTime());
        vo.setCreatedTime(b.getCreatedTime());
        vo.setUpdatedTime(b.getUpdatedTime());
        return vo;
    }

    private static String statusDesc(Integer status) {
        if (status == null) {
            return null;
        }
        return status == ApBanner.STATUS_ENABLED ? "启用" : "停用";
    }

    // ==================== 审计摘要与小工具 ====================

    private static String describeFields(Prepared p) {
        return "备注=" + p.title + ", 顺序=" + p.sortOrder + ", 跳转=" + p.linkUrl
            + ", 时间窗=" + describeWindow(p.startTime, p.endTime);
    }

    /** 变更摘要：只写真正变了的字段 */
    private static String describeChange(ApBanner before, Prepared p) {
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(before.getTitle(), p.title)) {
            parts.add("备注「" + safe(before.getTitle()) + "」->「" + p.title + "」");
        }
        if (!Objects.equals(before.getImageUrl(), p.imageUrl)) {
            parts.add("图片已更换");
        }
        if (!Objects.equals(before.getLinkUrl(), p.linkUrl)) {
            parts.add("跳转 " + safe(before.getLinkUrl()) + " -> " + p.linkUrl);
        }
        if (!Objects.equals(before.getSortOrder(), p.sortOrder)) {
            parts.add("顺序 " + before.getSortOrder() + " -> " + p.sortOrder);
        }
        if (!Objects.equals(before.getStartTime(), p.startTime)
            || !Objects.equals(before.getEndTime(), p.endTime)) {
            parts.add("时间窗 " + describeWindow(before.getStartTime(), before.getEndTime())
                + " -> " + describeWindow(p.startTime, p.endTime));
        }
        return parts.isEmpty() ? "无字段变化" : String.join("; ", parts);
    }

    private static String describeWindow(Date start, Date end) {
        String s = start == null ? "不限" : fmt(start);
        String e = end == null ? "不限" : fmt(end);
        return s + " ~ " + e;
    }

    /** {@code DateTimeFormatter} 不收 {@code Date}，转一次 LocalDateTime（仅格式化用途） */
    private static String fmt(Date date) {
        return FORMAT_DATETIME.format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    /** 输出用的时刻格式（仅格式化，无解析歧义） */
    private static final DateTimeFormatter FORMAT_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 解析 {@code yyyy-MM-dd HH:mm:ss}；空串返回 null（=不限），格式非法返回 null（由调用方区分报错） */
    private static Date parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            LocalDateTime dt = LocalDateTime.parse(text.trim(), PARSE_DATETIME);
            return Date.from(dt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 理由校验：必填、上限 500。由控制器与服务各调一次 —— 服务是最后一道闸口。 */
    private static ResponseResult validateReason(String reason) {
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

    private static String reasonOf(AdminBannerSaveDto dto) {
        return dto == null ? null : reasonOf(dto.getReason());
    }

    private static String reasonOf(String reason) {
        return reason == null ? null : reason.trim();
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static int normalizePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    private static int normalizeSize(Integer size) {
        return (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;
    }

    private static Map<String, Object> pageData(List<?> list, long total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= DETAIL_MAX_LEN) {
            return text;
        }
        return text.substring(0, DETAIL_MAX_LEN);
    }

    /** 一次写入的中间结果：归一化后的字段值，或一条参数错误 */
    private static final class Prepared {

        private ResponseResult error;

        private String title;
        private String imageUrl;
        private String linkUrl;
        private Integer sortOrder;
        private Date startTime;
        private Date endTime;
    }
}
