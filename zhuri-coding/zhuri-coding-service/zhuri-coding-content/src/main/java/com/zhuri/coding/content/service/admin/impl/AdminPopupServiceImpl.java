package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.ops.ApPopupMapper;
import com.zhuri.coding.content.service.admin.AdminPopupService;
import com.zhuri.coding.content.service.ops.PopupCloseStore;
import com.zhuri.coding.model.admin.dtos.AdminPopupSaveDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminPopupVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.ops.pojos.ApPopup;
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
 * 弹窗管理实现。
 *
 * <p>接口层面的取舍见 {@link AdminPopupService}；CRUD 骨架与 {@code AdminBannerServiceImpl}
 * 同构（先读后条件更新、幂等闸口、审计分层），这里只写弹窗特有的两件事。
 *
 * <p><b>编辑结束时间要同步刷关闭记录的 TTL</b>：关闭记录存在 Redis，
 * TTL 上限就是弹窗的结束时间 —— 结束时间延后了、TTL 还停在旧值的话，
 * 关闭记录会在弹窗仍在投放期间过期，用户被重复弹一次。
 * 刷新失败只告警不回滚：最坏后果是重复弹一次，不值得让它拖垮编辑动作。
 *
 * <p><b>删除弹窗要清关闭记录</b>：弹窗没了，关闭记录成了孤儿 —— 靠 TTL 也会自亡，
 * 但 purge 一句 DEL 的事，顺手做掉（也兜底清掉可能存在的无 TTL 孤儿 key）。
 */
@Slf4j
@Service
public class AdminPopupServiceImpl implements AdminPopupService {

    private static final int REASON_MAX_LEN = 500;
    private static final int DETAIL_MAX_LEN = 1000;
    private static final int TITLE_MAX_LEN = 100;
    private static final int CONTENT_MAX_LEN = 2000;
    private static final int URL_MAX_LEN = 500;
    private static final int BUTTON_MAX_LEN = 50;

    private static final DateTimeFormatter PARSE_DATETIME =
        DateTimeFormatter.ofPattern("uuuu-M-d H:m:s").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FORMAT_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ApPopupMapper apPopupMapper;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    @Autowired
    private PopupCloseStore popupCloseStore;

    // ==================== 读 ====================

    @Override
    public ResponseResult page(String keyword, Integer status, Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        LambdaQueryWrapper<ApPopup> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ApPopup::getTitle, keyword.trim());
        }
        if (status != null) {
            if (status != ApPopup.STATUS_DISABLED && status != ApPopup.STATUS_ENABLED) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的状态：" + status + "（0-停用 1-启用）");
            }
            wrapper.eq(ApPopup::getStatus, status);
        }
        wrapper.orderByDesc(ApPopup::getId);

        IPage<ApPopup> result = apPopupMapper.selectPage(new Page<>(p, s), wrapper);
        List<AdminPopupVO> list = result.getRecords().stream()
            .map(this::toVO)
            .collect(Collectors.toList());
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    @Override
    public ResponseResult detail(Long id) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "弹窗ID不合法");
        }
        ApPopup popup = apPopupMapper.selectById(id);
        if (popup == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "弹窗不存在");
        }
        return ResponseResult.okResult(toVO(popup));
    }

    // ==================== 新建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult create(AdminPopupSaveDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_CREATE, TARGET_POPUP, null, reasonOf(dto));
        try {
            return doCreate(dto, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doCreate(AdminPopupSaveDto dto, ApAdminAuditLog audit) {
        Prepared prepared = prepare(dto);
        if (prepared.error != null) {
            return prepared.error;
        }
        ApPopup entity = new ApPopup();
        applyFields(entity, prepared);
        entity.setStatus(ApPopup.STATUS_DISABLED);
        Date now = new Date();
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        apPopupMapper.insert(entity);

        audit.setTargetId(String.valueOf(entity.getId()));
        audit.setDetail(truncate("新建弹窗：" + describeFields(prepared)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminPopup] 新建弹窗 id={}, title={}, {} ~ {}",
            entity.getId(), prepared.title, fmt(prepared.startTime), fmt(prepared.endTime));
        return ResponseResult.okResult(toVO(apPopupMapper.selectById(entity.getId())));
    }

    // ==================== 编辑 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult update(Long id, AdminPopupSaveDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_UPDATE, TARGET_POPUP, id == null ? null : String.valueOf(id), reasonOf(dto));
        try {
            return doUpdate(id, dto, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doUpdate(Long id, AdminPopupSaveDto dto, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "弹窗ID不合法");
        }
        ApPopup before = apPopupMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "弹窗不存在");
        }
        Prepared prepared = prepare(dto);
        if (prepared.error != null) {
            return prepared.error;
        }
        if (!changed(before, prepared)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "信息与当前一致，无需保存");
        }

        apPopupMapper.update(null, updateWrapper(prepared).eq(ApPopup::getId, id));

        // 结束时间变了 → 关闭记录的 TTL 跟着变，否则延后结束的弹窗会在投放期内被重复弹出
        if (!Objects.equals(before.getEndTime(), prepared.endTime)) {
            popupCloseStore.refreshTtl(id, prepared.endTime);
        }

        audit.setDetail(truncate(describeChange(before, prepared)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminPopup] 编辑弹窗 id={}", id);
        return ResponseResult.okResult(toVO(apPopupMapper.selectById(id)));
    }

    // ==================== 启停 / 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult enable(Long id, String reason) {
        return toggleStatus(id, ApPopup.STATUS_ENABLED, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult disable(Long id, String reason) {
        return toggleStatus(id, ApPopup.STATUS_DISABLED, reason);
    }

    private ResponseResult toggleStatus(Long id, int targetStatus, String reason) {
        String action = targetStatus == ApPopup.STATUS_ENABLED ? ACTION_ENABLE : ACTION_DISABLE;
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            action, TARGET_POPUP, id == null ? null : String.valueOf(id), reason.trim());
        try {
            if (id == null || id <= 0) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "弹窗ID不合法");
            }
            ApPopup before = apPopupMapper.selectById(id);
            if (before == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "弹窗不存在");
            }
            if (before.getStatus() != null && before.getStatus() == targetStatus) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "当前已" + statusDesc(targetStatus) + "，无需"
                        + (targetStatus == ApPopup.STATUS_ENABLED ? "启用" : "停用"));
            }
            int rows = apPopupMapper.update(null, new LambdaUpdateWrapper<ApPopup>()
                .set(ApPopup::getStatus, targetStatus)
                .set(ApPopup::getUpdatedTime, new Date())
                .eq(ApPopup::getId, id)
                .eq(ApPopup::getStatus, before.getStatus()));
            if (rows == 0) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "状态已被其他操作改变，请刷新后重试");
            }
            audit.setDetail(truncate("弹窗「" + safe(before.getTitle()) + "」"
                + statusDesc(before.getStatus()) + " -> " + statusDesc(targetStatus)));
            auditRecorder.recordSuccess(audit);
            log.info("[AdminPopup] {} 弹窗 id={}", action, id);
            return ResponseResult.okResult(toVO(apPopupMapper.selectById(id)));
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
            ACTION_DELETE, TARGET_POPUP, id == null ? null : String.valueOf(id), reason.trim());
        try {
            return doDelete(id, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doDelete(Long id, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "弹窗ID不合法");
        }
        ApPopup before = apPopupMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "弹窗不存在");
        }
        if (before.getStatus() != null && before.getStatus() == ApPopup.STATUS_ENABLED) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "启用中的弹窗不能删除，请先停用（停用后 C 端立即不可见）");
        }
        int rows = apPopupMapper.delete(new LambdaQueryWrapper<ApPopup>()
            .eq(ApPopup::getId, id)
            .eq(ApPopup::getStatus, ApPopup.STATUS_DISABLED));
        if (rows == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "状态已被其他操作改变，请刷新后重试");
        }
        // 弹窗没了，关闭记录成了孤儿：purge 顺带清掉可能存在的无 TTL 孤儿 key
        popupCloseStore.purge(id);
        audit.setDetail(truncate("删除停用弹窗：「" + safe(before.getTitle()) + "」"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminPopup] 删除弹窗 id={}, title={}", id, before.getTitle());
        return ResponseResult.okResult();
    }

    // ==================== 入参校验与装配 ====================

    private Prepared prepare(AdminPopupSaveDto dto) {
        Prepared p = new Prepared();
        if (dto == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
            return p;
        }
        String title = trimToNull(dto.getTitle());
        if (title == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写弹窗标题");
            return p;
        }
        if (title.length() > TITLE_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "标题不能超过" + TITLE_MAX_LEN + "字");
            return p;
        }
        String content = trimToNull(dto.getContent());
        if (content != null && content.length() > CONTENT_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "正文不能超过" + CONTENT_MAX_LEN + "字");
            return p;
        }
        String imageUrl = trimToNull(dto.getImageUrl());
        if (imageUrl != null && imageUrl.length() > URL_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "配图地址不能超过" + URL_MAX_LEN + "个字符");
            return p;
        }
        // 按钮文案与跳转必须成对出现：只有文案没跳转 = 一个点了没反应的按钮；
        // 只有跳转没文案 = 渲染不出按钮。都不填 = 只有"知道了"关闭按钮，也是合法形态。
        String buttonText = trimToNull(dto.getButtonText());
        String linkUrl = trimToNull(dto.getLinkUrl());
        if ((buttonText == null) != (linkUrl == null)) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "按钮文案与跳转地址必须同时填写（都不填则只显示「知道了」关闭按钮）");
            return p;
        }
        if (buttonText != null && buttonText.length() > BUTTON_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "按钮文案不能超过" + BUTTON_MAX_LEN + "字");
            return p;
        }
        if (linkUrl != null) {
            ResponseResult linkError = validateLink(linkUrl);
            if (linkError != null) {
                p.error = linkError;
                return p;
            }
        }
        Date startTime = parseDateTime(dto.getStartTime());
        if (startTime == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "生效开始必填，格式 yyyy-MM-dd HH:mm:ss（收到：" + safe(dto.getStartTime()) + "）");
            return p;
        }
        Date endTime = parseDateTime(dto.getEndTime());
        if (endTime == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "生效结束必填，格式 yyyy-MM-dd HH:mm:ss（收到：" + safe(dto.getEndTime()) + "）");
            return p;
        }
        if (!endTime.after(startTime)) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "生效结束不得早于开始");
            return p;
        }

        p.title = title;
        p.content = content;
        p.imageUrl = imageUrl;
        p.buttonText = buttonText;
        p.linkUrl = linkUrl;
        p.startTime = startTime;
        p.endTime = endTime;
        return p;
    }

    /** 与 Banner 同一白名单：站内路由（/ 开头）或 http(s) 外链，其余（含 javascript:）拒绝 */
    private static ResponseResult validateLink(String linkUrl) {
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

    private static void applyFields(ApPopup entity, Prepared p) {
        entity.setTitle(p.title);
        entity.setContent(p.content);
        entity.setImageUrl(p.imageUrl);
        entity.setButtonText(p.buttonText);
        entity.setLinkUrl(p.linkUrl);
        entity.setStartTime(p.startTime);
        entity.setEndTime(p.endTime);
    }

    private static LambdaUpdateWrapper<ApPopup> updateWrapper(Prepared p) {
        return new LambdaUpdateWrapper<ApPopup>()
            .set(ApPopup::getTitle, p.title)
            .set(ApPopup::getContent, p.content)
            .set(ApPopup::getImageUrl, p.imageUrl)
            .set(ApPopup::getButtonText, p.buttonText)
            .set(ApPopup::getLinkUrl, p.linkUrl)
            .set(ApPopup::getStartTime, p.startTime)
            .set(ApPopup::getEndTime, p.endTime)
            .set(ApPopup::getUpdatedTime, new Date());
    }

    private static boolean changed(ApPopup before, Prepared p) {
        return !Objects.equals(before.getTitle(), p.title)
            || !Objects.equals(before.getContent(), p.content)
            || !Objects.equals(before.getImageUrl(), p.imageUrl)
            || !Objects.equals(before.getButtonText(), p.buttonText)
            || !Objects.equals(before.getLinkUrl(), p.linkUrl)
            || !Objects.equals(before.getStartTime(), p.startTime)
            || !Objects.equals(before.getEndTime(), p.endTime);
    }

    // ==================== 出参装配 ====================

    private AdminPopupVO toVO(ApPopup popup) {
        if (popup == null) {
            return null;
        }
        AdminPopupVO vo = new AdminPopupVO();
        vo.setId(popup.getId());
        vo.setTitle(popup.getTitle());
        vo.setContent(popup.getContent());
        vo.setImageUrl(popup.getImageUrl());
        vo.setButtonText(popup.getButtonText());
        vo.setLinkUrl(popup.getLinkUrl());
        vo.setStatus(popup.getStatus());
        vo.setStatusDesc(statusDesc(popup.getStatus()));
        vo.setStartTime(popup.getStartTime());
        vo.setEndTime(popup.getEndTime());
        vo.setCreatedTime(popup.getCreatedTime());
        vo.setUpdatedTime(popup.getUpdatedTime());
        return vo;
    }

    private static String statusDesc(Integer status) {
        if (status == null) {
            return null;
        }
        return status == ApPopup.STATUS_ENABLED ? "启用" : "停用";
    }

    // ==================== 审计摘要与小工具 ====================

    private static String describeFields(Prepared p) {
        return "标题=" + p.title + ", 窗口=" + fmt(p.startTime)
            + " ~ " + fmt(p.endTime)
            + ", 按钮=" + (p.buttonText == null ? "无（仅关闭）" : p.buttonText + " -> " + p.linkUrl);
    }

    private static String describeChange(ApPopup before, Prepared p) {
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(before.getTitle(), p.title)) {
            parts.add("标题「" + safe(before.getTitle()) + "」->「" + p.title + "」");
        }
        if (!Objects.equals(before.getContent(), p.content)) {
            parts.add("正文已" + (p.content == null ? "清空" : "修改"));
        }
        if (!Objects.equals(before.getImageUrl(), p.imageUrl)) {
            parts.add("配图已" + (p.imageUrl == null ? "清空" : "更换"));
        }
        if (!Objects.equals(before.getButtonText(), p.buttonText)
            || !Objects.equals(before.getLinkUrl(), p.linkUrl)) {
            parts.add("按钮 " + describeButton(before.getButtonText(), before.getLinkUrl())
                + " -> " + describeButton(p.buttonText, p.linkUrl));
        }
        if (!Objects.equals(before.getStartTime(), p.startTime)
            || !Objects.equals(before.getEndTime(), p.endTime)) {
            parts.add("窗口 " + describeWindow(before.getStartTime(), before.getEndTime())
                + " -> " + describeWindow(p.startTime, p.endTime));
        }
        return parts.isEmpty() ? "无字段变化" : String.join("; ", parts);
    }

    private static String describeButton(String buttonText, String linkUrl) {
        return buttonText == null ? "无（仅关闭）" : buttonText + " -> " + linkUrl;
    }

    private static String describeWindow(Date start, Date end) {
        return fmt(start) + " ~ " + fmt(end);
    }

    /** {@code DateTimeFormatter} 不收 {@code Date}，转一次 LocalDateTime（仅格式化用途） */
    private static String fmt(Date date) {
        return FORMAT_DATETIME.format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

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

    private static String reasonOf(AdminPopupSaveDto dto) {
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

    private static final class Prepared {

        private ResponseResult error;

        private String title;
        private String content;
        private String imageUrl;
        private String buttonText;
        private String linkUrl;
        private Date startTime;
        private Date endTime;
    }
}
