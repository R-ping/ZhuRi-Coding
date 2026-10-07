package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.activity.ApActivityMapper;
import com.zhuri.coding.content.mapper.topic.TopicMapper;
import com.zhuri.coding.content.service.admin.AdminActivityService;
import com.zhuri.coding.model.activity.ActivityStatus;
import com.zhuri.coding.model.activity.ActivityTaxonomy;
import com.zhuri.coding.model.activity.pojos.ApActivity;
import com.zhuri.coding.model.admin.dtos.AdminActivitySaveDto;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminActivityVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.topic.pojos.ApTopic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 活动管理实现（CMS）。
 *
 * <p>接口层面的取舍见 {@link AdminActivityService}。这里只记载实现上几个不那么显然的决定。
 *
 * <p><b>所有写操作都包在 try/catch 里 + {@code recordFailure}</b>：与
 * {@code AdminOpsConfigServiceImpl} 同构 —— 成功记录随业务事务提交，失败记录另起事务。
 * 抛出的异常继续往上抛（不吞），因为调用方需要真实的失败原因。
 * 注意"校验不通过"走的是<b>直接 return</b>而不是抛异常：一次参数写错不值得在审计里
 * 留一条失败记录（那会让"谁试过但没成功"这条线索被大量手误淹没）。
 *
 * <p><b>状态类动作一律"先读后条件更新"</b>：先读出来判断能不能做（给人一句准确的错误），
 * 再用带 {@code WHERE status = 读到的那个值} 的 UPDATE 落地。多出的这个条件不是冗余 ——
 * 它挡住的是"两个运营同时点上线"以及"页面停在旧状态上重复提交"，这两种情况下
 * 第二次更新会命中 0 行，于是能明确回一句"状态已被其他操作改变，请刷新后重试"，
 * 而不是静默地再写一遍。
 *
 * <p><b>编辑用 {@code LambdaUpdateWrapper} 而不是 {@code updateById}</b>：{@code updateById}
 * 会跳过实体里为 null 的字段（这是它的默认行为，也是它被称为"部分更新"的原因）。
 * 而"清空描述"、"清空封面"、"解除话题关联"恰恰就是要把字段写成 null ——
 * 用 {@code updateById} 这三种操作会静默失效：接口返回成功，库里原样不变。
 * 显式逐个 {@code set} 就没有这个歧义。
 *
 * <p><b>列表排序用 {@code id DESC}</b>而不是 {@code created_time DESC}：{@code id} 是自增主键，
 * 倒序天然就是"最近建的在前"，且走聚簇索引；而 {@code created_time} 上没有索引，
 * 对一个只按状态筛的列表来说会多出一次 filesort。
 */
@Slf4j
@Service
public class AdminActivityServiceImpl implements AdminActivityService {

    /** 理由上限，与 {@code ap_admin_audit_log.reason} 列等长 */
    private static final int REASON_MAX_LEN = 500;

    /** 审计 detail 摘要上限（列是 text，但摘要不需要长文） */
    private static final int DETAIL_MAX_LEN = 1000;

    /**
     * 入参日期格式：{@code uuuu-M-d}，即 {@code 2026-10-06} 与 {@code 2026-10-6} 都收。
     *
     * <p>用 {@code uuuu}（proleptic year）而不是 {@code yyyy}：{@code yyyy} 是"纪元年"，
     * 在 {@link ResolverStyle#STRICT} 下解析需要同时给出纪元（AD/BC），
     * 而请求里不会有人带这个。{@code uuuu} 让 STRICT 解析既严格又能正常工作 ——
     * 严格是必要的：SMART 模式会把 {@code 2026-02-31} 悄悄顺延成 3 月 3 日。
     */
    private static final DateTimeFormatter PARSE_DATE =
        DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT);

    /** 出参 / 日志用的日期格式（仅格式化，无解析，故用 {@code yyyy} 没有歧义） */
    private static final DateTimeFormatter FORMAT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private ApActivityMapper apActivityMapper;

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    // ==================== 读 ====================

    @Override
    public ResponseResult page(String keyword, String status, String type, String category,
                               Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        LambdaQueryWrapper<ApActivity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ApActivity::getTitle, keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            String code = ActivityStatus.normalize(status);
            // 拼错状态名要报错而不是返回空列表：空列表会让运营以为"这个状态确实没数据"，
            // 于是一直在别处找原因。这与"列表不过滤状态"是一样的取向 —— 后台要说真话。
            if (!ActivityStatus.isValid(code)) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的活动状态：" + status + "（可选：" + joinCodes(ActivityStatus.allStatuses()) + "）");
            }
            wrapper.eq(ApActivity::getStatus, code);
        }
        if (type != null && !type.isBlank()) {
            String code = ActivityTaxonomy.normalize(type);
            if (!ActivityTaxonomy.isValidType(code)) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的活动类型：" + type + "（可选：" + ActivityTaxonomy.typesAsText() + "）");
            }
            wrapper.eq(ApActivity::getActivityType, code);
        }
        if (category != null && !category.isBlank()) {
            String code = ActivityTaxonomy.normalize(category);
            if (!ActivityTaxonomy.isValidCategory(code)) {
                return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "无法识别的活动分类：" + category + "（可选：" + ActivityTaxonomy.categoriesAsText() + "）");
            }
            wrapper.eq(ApActivity::getCategory, code);
        }
        wrapper.orderByDesc(ApActivity::getId);

        IPage<ApActivity> result = apActivityMapper.selectPage(new Page<>(p, s), wrapper);
        Map<Long, String> topicNames = topicNamesOf(result.getRecords());
        List<AdminActivityVO> list = result.getRecords().stream()
            .map(a -> toVO(a, topicNames))
            .collect(Collectors.toList());
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    @Override
    public ResponseResult detail(Long id) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "活动ID不合法");
        }
        ApActivity activity = apActivityMapper.selectById(id);
        if (activity == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "活动不存在");
        }
        return ResponseResult.okResult(toVO(activity, topicNameOf(activity)));
    }

    // ==================== 新建 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult create(AdminActivitySaveDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_CREATE, TARGET_ACTIVITY, null, reasonOf(dto));
        try {
            return doCreate(dto, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doCreate(AdminActivitySaveDto dto, ApAdminAuditLog audit) {
        Prepared prepared = prepare(dto);
        if (prepared.error != null) {
            return prepared.error;
        }

        ApActivity entity = new ApActivity();
        applyFields(entity, prepared);
        // 新建一律先落草稿：上线是另一个动作、另一条审计（见接口注释）
        entity.setStatus(ActivityStatus.DRAFT);
        // 统计列显式置 0，不依赖 DDL 默认值 —— 依赖默认值的话，一旦有人改了列定义，
        // 这里就会静默插入 NULL，而 C 端把 NULL 当 0 展示之前可能先崩在别处
        entity.setTotalParticipants(0);
        entity.setTotalReadCount(0L);
        Date now = new Date();
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        apActivityMapper.insert(entity);

        audit.setTargetId(String.valueOf(entity.getId()));
        audit.setDetail(truncate("新建活动：" + describeFields(prepared)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminActivity] 新建活动 id={}, title={}, type={}, category={}, {} ~ {}, topicId={}",
            entity.getId(), prepared.title, prepared.activityType, prepared.category,
            fmt(prepared.startDate), fmt(prepared.endDate), prepared.topicId);

        // 新建后回读一次，让出参就是库里的样子
        ApActivity created = apActivityMapper.selectById(entity.getId());
        return ResponseResult.okResult(toVO(created, topicNameOf(created)));
    }

    // ==================== 编辑 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult update(Long id, AdminActivitySaveDto dto) {
        ResponseResult invalid = validateReason(dto == null ? null : dto.getReason());
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_UPDATE, TARGET_ACTIVITY, id == null ? null : String.valueOf(id), reasonOf(dto));
        try {
            return doUpdate(id, dto, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doUpdate(Long id, AdminActivitySaveDto dto, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "活动ID不合法");
        }
        ApActivity before = apActivityMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "活动不存在");
        }
        Prepared prepared = prepare(dto);
        if (prepared.error != null) {
            return prepared.error;
        }

        String beforeStatus = ActivityStatus.normalize(before.getStatus());
        // 已发布的活动：日期一改，阶段就要重算。把结束日期往后延，"已结束"会重新变成"进行中"
        // —— 这是"活动延期"的正常用法。草稿与已下线不重算：它们本来就与时间无关，上线时再算。
        String afterStatus = beforeStatus;
        if (ActivityStatus.isPublished(beforeStatus)) {
            String recomputed = ActivityStatus.phaseOf(prepared.startDate, prepared.endDate, new Date());
            if (recomputed != null) {
                afterStatus = recomputed;
            }
        }

        if (!changed(before, prepared, afterStatus)) {
            // 与现状一致时报错而不是静默成功：页面没刷新、或误点保存时，
            // 静默成功会让运营以为"我刚改的生效了"，而审计里多一条没有差别的记录
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动信息与当前一致，无需保存");
        }

        LambdaUpdateWrapper<ApActivity> wrapper = fullUpdateWrapper(prepared, afterStatus)
            .eq(ApActivity::getId, id);
        apActivityMapper.update(null, wrapper);
        // 更新后重新读一次：出参要给的是库里的实际状态，而不是"我打算写成什么"。
        // 也正因为重读了，话题名要从这条记录上取（而不是从入参取）——
        // 清空关联话题后，入参和库里都是 null，两者一致。
        ApActivity after = apActivityMapper.selectById(id);
        if (after == null) {
            // 极小概率：更新与重读之间这条草稿被并发删掉了。给一句能看懂的提示，
            // 而不是让 toVO(null) 抛 NPE 变成 500
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "活动已被删除");
        }

        audit.setDetail(truncate(describeChange(before, prepared, beforeStatus, afterStatus)));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminActivity] 编辑活动 id={}, 状态 {} -> {}, {} ~ {}",
            id, beforeStatus, afterStatus, fmt(prepared.startDate), fmt(prepared.endDate));

        return ResponseResult.okResult(toVO(after, topicNameOf(after)));
    }

    // ==================== 上线 / 下线 / 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult publish(Long id, String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_PUBLISH, TARGET_ACTIVITY, id == null ? null : String.valueOf(id), reasonOf(reason));
        try {
            return doPublish(id, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doPublish(Long id, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "活动ID不合法");
        }
        ApActivity before = apActivityMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "活动不存在");
        }
        String current = ActivityStatus.normalize(before.getStatus());
        if (!ActivityStatus.canPublish(current)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动当前状态为「" + ActivityStatus.describe(current) + "」，无需上线"
                    + "（只有草稿与已下线可以上线）");
        }
        // 按日期一次算准该落的阶段：重新上线一条早已过期的活动，直接就是"已结束"，
        // 不会先短暂地显示成"即将开始"再被定时任务纠正
        String target = ActivityStatus.phaseOf(before.getStartDate(), before.getEndDate(), new Date());
        if (target == null) {
            // 起止日期为空是脏数据（DDL 上 NOT NULL）。这里给一句能照着做的提示，
            // 而不是让 NPE 变成 500 —— 脏数据带来的 500 会让人去查代码，而问题在数据。
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动起止日期不完整，请先补全后再上线");
        }

        int rows = apActivityMapper.update(null, new LambdaUpdateWrapper<ApActivity>()
            .set(ApActivity::getStatus, target)
            .set(ApActivity::getUpdatedTime, new Date())
            .eq(ApActivity::getId, id)
            .eq(ApActivity::getStatus, current));
        if (rows == 0) {
            // 条件更新命中 0 行 = 读出来之后状态被别人改过（并发上线 / 页面停在旧状态重复提交）
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动状态已被其他操作改变，请刷新后重试");
        }

        audit.setDetail(truncate("活动「" + safeName(before.getTitle()) + "」"
            + ActivityStatus.describe(current) + " -> " + ActivityStatus.describe(target)
            + "（" + fmt(before.getStartDate()) + " ~ " + fmt(before.getEndDate()) + "）"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminActivity] 上线活动 id={}, {} -> {}", id, current, target);
        return ResponseResult.okResult(toVO(apActivityMapper.selectById(id),
            topicNameOf(before)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult offline(Long id, String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_OFFLINE, TARGET_ACTIVITY, id == null ? null : String.valueOf(id), reasonOf(reason));
        try {
            return doOffline(id, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doOffline(Long id, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "活动ID不合法");
        }
        ApActivity before = apActivityMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "活动不存在");
        }
        String current = ActivityStatus.normalize(before.getStatus());
        if (!ActivityStatus.canOffline(current)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动当前状态为「" + ActivityStatus.describe(current) + "」，无需下线"
                    + "（只有已发布的活动可以下线）");
        }

        int rows = apActivityMapper.update(null, new LambdaUpdateWrapper<ApActivity>()
            .set(ApActivity::getStatus, ActivityStatus.OFFLINE)
            .set(ApActivity::getUpdatedTime, new Date())
            .eq(ApActivity::getId, id)
            .eq(ApActivity::getStatus, current));
        if (rows == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动状态已被其他操作改变，请刷新后重试");
        }

        audit.setDetail(truncate("活动「" + safeName(before.getTitle()) + "」"
            + ActivityStatus.describe(current) + " -> 已下线"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminActivity] 下线活动 id={}, {} -> offline", id, current);
        return ResponseResult.okResult(toVO(apActivityMapper.selectById(id),
            topicNameOf(before)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult delete(Long id, String reason) {
        ResponseResult invalid = validateReason(reason);
        if (invalid != null) {
            return invalid;
        }
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_DELETE, TARGET_ACTIVITY, id == null ? null : String.valueOf(id), reasonOf(reason));
        try {
            return doDelete(id, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doDelete(Long id, ApAdminAuditLog audit) {
        if (id == null || id <= 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "活动ID不合法");
        }
        ApActivity before = apActivityMapper.selectById(id);
        if (before == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "活动不存在");
        }
        String current = ActivityStatus.normalize(before.getStatus());
        if (!ActivityStatus.isDraft(current)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "只有草稿可以删除；当前状态为「" + ActivityStatus.describe(current)
                    + "」，如需从列表撤下请使用下线");
        }

        int rows = apActivityMapper.delete(new LambdaQueryWrapper<ApActivity>()
            .eq(ApActivity::getId, id)
            .eq(ApActivity::getStatus, ActivityStatus.DRAFT));
        if (rows == 0) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动状态已被其他操作改变，请刷新后重试");
        }

        // 删除后 id 仍在审计里 —— 这是审计表要存 id 而不是存外键的原因：
        // 记录要能在被记录的对象消失之后依然读得懂
        audit.setDetail(truncate("删除草稿活动：「" + safeName(before.getTitle()) + "」（"
            + fmt(before.getStartDate()) + " ~ " + fmt(before.getEndDate()) + "）"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminActivity] 删除草稿活动 id={}, title={}", id, before.getTitle());
        return ResponseResult.okResult();
    }

    // ==================== 入参校验与装配 ====================

    /**
     * 入参校验 + 归一化。
     *
     * <p><b>这一层放在服务里而不是只在控制器里</b>：控制器校验挡的是"这个 HTTP 请求合不合规矩"，
     * 服务要挡的是"这份数据能不能写进库"。后者对任何调用方都成立 —— 将来若有别的入口
     * （批量导入、定时任务）复用这个方法，它不会因为绕过了控制器就失去校验。
     *
     * @return 校验结果；{@code error} 非 null 表示不通过
     */
    private Prepared prepare(AdminActivitySaveDto dto) {
        Prepared p = new Prepared();
        if (dto == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "参数不完整");
            return p;
        }

        String title = trimToNull(dto.getTitle());
        if (title == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "请填写活动标题");
            return p;
        }
        if (title.length() > TITLE_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动标题不能超过" + TITLE_MAX_LEN + "字");
            return p;
        }

        String description = trimToNull(dto.getDescription());
        if (description != null && description.length() > DESC_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "活动描述不能超过" + DESC_MAX_LEN + "字");
            return p;
        }

        String coverImage = trimToNull(dto.getCoverImage());
        if (coverImage != null && coverImage.length() > COVER_MAX_LEN) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "封面地址不能超过" + COVER_MAX_LEN + "个字符");
            return p;
        }

        String type = ActivityTaxonomy.normalize(dto.getActivityType());
        if (type == null) {
            type = ActivityTaxonomy.DEFAULT_TYPE;
        } else if (!ActivityTaxonomy.isValidType(type)) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "无法识别的活动类型：" + dto.getActivityType()
                    + "（可选：" + ActivityTaxonomy.typesAsText() + "）");
            return p;
        }

        String category = ActivityTaxonomy.normalize(dto.getCategory());
        if (category == null) {
            category = ActivityTaxonomy.DEFAULT_CATEGORY;
        } else if (!ActivityTaxonomy.isValidCategory(category)) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "无法识别的活动分类：" + dto.getCategory()
                    + "（可选：" + ActivityTaxonomy.categoriesAsText() + "）");
            return p;
        }

        Date startDate = parseDate(dto.getStartDate());
        if (startDate == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "开始日期格式应为 yyyy-MM-dd（收到：" + safeName(dto.getStartDate()) + "）");
            return p;
        }
        Date endDate = parseDate(dto.getEndDate());
        if (endDate == null) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "结束日期格式应为 yyyy-MM-dd（收到：" + safeName(dto.getEndDate()) + "）");
            return p;
        }
        if (endDate.before(startDate)) {
            p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "结束日期不能早于开始日期");
            return p;
        }

        Long topicId = dto.getTopicId();
        String topicName = null;
        if (topicId != null) {
            if (topicId <= 0) {
                p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "关联话题ID不合法");
                return p;
            }
            // 活动表对 topic_id 没有外键。不校验存在性的话，写进去一个不存在的 id 不会报错，
            // 只会在 C 端表现为"活动关联了一个点进去 404 的话题"—— 那种问题要到用户投诉才发现。
            ApTopic topic = topicMapper.selectById(topicId);
            if (topic == null) {
                p.error = ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                    "关联话题不存在或已被删除（话题ID：" + topicId + "）");
                return p;
            }
            topicName = safeName(topic.getName());
        }

        p.title = title;
        p.description = description;
        p.coverImage = coverImage;
        p.activityType = type;
        p.category = category;
        p.startDate = startDate;
        p.endDate = endDate;
        p.topicId = topicId;
        p.topicName = topicName;
        return p;
    }

    /** 把归一化后的字段写进实体（新建用） */
    private static void applyFields(ApActivity entity, Prepared p) {
        entity.setTitle(p.title);
        entity.setDescription(p.description);
        entity.setCoverImage(p.coverImage);
        entity.setActivityType(p.activityType);
        entity.setCategory(p.category);
        entity.setStartDate(p.startDate);
        entity.setEndDate(p.endDate);
        entity.setTopicId(p.topicId);
    }

    /**
     * 编辑用的完整 SET 子句。
     *
     * <p>每一个可编辑列都显式写出来，<b>包含可能为 null 的那三列</b>（描述、封面、关联话题）——
     * 这是"清空"能被执行的唯一方式，理由见类注释。
     */
    private static LambdaUpdateWrapper<ApActivity> fullUpdateWrapper(Prepared p, String status) {
        return new LambdaUpdateWrapper<ApActivity>()
            .set(ApActivity::getTitle, p.title)
            .set(ApActivity::getDescription, p.description)
            .set(ApActivity::getCoverImage, p.coverImage)
            .set(ApActivity::getActivityType, p.activityType)
            .set(ApActivity::getCategory, p.category)
            .set(ApActivity::getStartDate, p.startDate)
            .set(ApActivity::getEndDate, p.endDate)
            .set(ApActivity::getTopicId, p.topicId)
            .set(ApActivity::getStatus, status)
            .set(ApActivity::getUpdatedTime, new Date());
    }

    /**
     * 是否与当前记录有实质差异（幂等闸口）。
     *
     * <p>逐字段比对而不是"重新拼一个对象再 equals"：后者会把 {@code totalParticipants}、
     * {@code totalReadCount} 这些<b>不由运营维护</b>的统计列也算进来，
     * 于是活动只要有一个人参与过，这个比较就永远为"有差异"，闸口形同虚设。
     */
    private static boolean changed(ApActivity before, Prepared p, String afterStatus) {
        return !Objects.equals(before.getTitle(), p.title)
            || !Objects.equals(trimToNull(before.getDescription()), p.description)
            || !Objects.equals(trimToNull(before.getCoverImage()), p.coverImage)
            || !Objects.equals(ActivityTaxonomy.normalize(before.getActivityType()), p.activityType)
            || !Objects.equals(ActivityTaxonomy.normalize(before.getCategory()), p.category)
            || !sameDay(before.getStartDate(), p.startDate)
            || !sameDay(before.getEndDate(), p.endDate)
            || !Objects.equals(before.getTopicId(), p.topicId)
            || !Objects.equals(ActivityStatus.normalize(before.getStatus()), afterStatus);
    }

    // ==================== 出参装配 ====================

    private AdminActivityVO toVO(ApActivity a, Map<Long, String> topicNames) {
        if (a == null) {
            // 各调用点都已拦掉"读不到"的情况；这里再兜一层，
            // 让任何遗漏表现为"data 为 null"而不是 NPE 500
            return null;
        }
        AdminActivityVO vo = new AdminActivityVO();
        vo.setId(a.getId());
        vo.setTitle(a.getTitle());
        vo.setDescription(a.getDescription());
        vo.setCoverImage(a.getCoverImage());
        vo.setActivityType(ActivityTaxonomy.normalize(a.getActivityType()));
        vo.setCategory(ActivityTaxonomy.normalize(a.getCategory()));
        String status = ActivityStatus.normalize(a.getStatus());
        vo.setStatus(status);
        vo.setStatusDesc(ActivityStatus.describe(status));
        vo.setStartDate(a.getStartDate());
        vo.setEndDate(a.getEndDate());
        vo.setTopicId(a.getTopicId());
        if (a.getTopicId() != null) {
            // 话题被删时这里是 null：不隐藏、不报错，让下面的 topicId 原样显示，
            // 前端可以据此标出"话题已不存在"
            vo.setTopicName(topicNames.get(a.getTopicId()));
        }
        vo.setTotalParticipants(a.getTotalParticipants() == null ? 0 : a.getTotalParticipants());
        vo.setTotalReadCount(a.getTotalReadCount() == null ? 0L : a.getTotalReadCount());
        vo.setCreatedTime(a.getCreatedTime());
        vo.setUpdatedTime(a.getUpdatedTime());
        return vo;
    }

    /**
     * 批量取关联话题名。
     *
     * <p>一次 {@code selectBatchIds} 而不是每行查一次：列表一页 20 条，
     * 逐行查就是 20 次往返，而这里只需要 1 次。空集合直接返回空 Map，不去打库 ——
     * {@code selectBatchIds} 收到空集合会拼出 {@code WHERE id IN ()} 这种非法 SQL。
     *
     * <p>入参允许带 null（活动大多不关联话题）：在方法内部过滤并去重，
     * 免得每个调用点各写一遍 {@code filter(Objects::nonNull)}。
     */
    private Map<Long, String> loadTopicNames(Collection<Long> rawIds) {
        Set<Long> ids = rawIds == null ? new LinkedHashSet<>()
            : rawIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return topicMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(ApTopic::getId, t -> safeName(t.getName()), (a, b) -> a));
    }

    /** 取一批活动关联的话题名 */
    private Map<Long, String> topicNamesOf(List<ApActivity> activities) {
        return loadTopicNames(activities.stream().map(ApActivity::getTopicId)
            .collect(Collectors.toList()));
    }

    /** 取单个（或没有）关联话题的名字；不关联时返回空 Map，而不是去打一次库 */
    private Map<Long, String> topicNameOf(ApActivity activity) {
        if (activity == null || activity.getTopicId() == null) {
            return Collections.emptyMap();
        }
        return loadTopicNames(List.of(activity.getTopicId()));
    }

    // ==================== 审计摘要 ====================

    private static String describeFields(Prepared p) {
        List<String> parts = new ArrayList<>();
        parts.add("标题=" + p.title);
        parts.add("类型=" + p.activityType);
        parts.add("分类=" + p.category);
        parts.add("日期=" + fmt(p.startDate) + " ~ " + fmt(p.endDate));
        parts.add("关联话题=" + describeTopic(p.topicId, p.topicName));
        return String.join(", ", parts);
    }

    /** 变更摘要：只写真正变了的字段，没变的字段不出现在记录里 */
    private static String describeChange(ApActivity before, Prepared p,
                                         String beforeStatus, String afterStatus) {
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(before.getTitle(), p.title)) {
            parts.add("标题「" + safeName(before.getTitle()) + "」->「" + p.title + "」");
        }
        if (!Objects.equals(trimToNull(before.getDescription()), p.description)) {
            parts.add("描述已" + (p.description == null ? "清空" : "修改"));
        }
        if (!Objects.equals(trimToNull(before.getCoverImage()), p.coverImage)) {
            parts.add("封面已" + (p.coverImage == null ? "清空" : "更换"));
        }
        if (!Objects.equals(ActivityTaxonomy.normalize(before.getActivityType()), p.activityType)) {
            parts.add("类型 " + before.getActivityType() + " -> " + p.activityType);
        }
        if (!Objects.equals(ActivityTaxonomy.normalize(before.getCategory()), p.category)) {
            parts.add("分类 " + before.getCategory() + " -> " + p.category);
        }
        if (!sameDay(before.getStartDate(), p.startDate) || !sameDay(before.getEndDate(), p.endDate)) {
            parts.add("日期 " + fmt(before.getStartDate()) + "~" + fmt(before.getEndDate())
                + " -> " + fmt(p.startDate) + "~" + fmt(p.endDate));
        }
        if (!Objects.equals(before.getTopicId(), p.topicId)) {
            parts.add("关联话题 " + describeTopic(before.getTopicId(), null)
                + " -> " + describeTopic(p.topicId, p.topicName));
        }
        if (!Objects.equals(beforeStatus, afterStatus)) {
            parts.add("状态 " + ActivityStatus.describe(beforeStatus)
                + " -> " + ActivityStatus.describe(afterStatus) + "（按新日期重算）");
        }
        // 兜底：理论上不会走到（changed 已经拦过），但审计宁可写一句"有变更"也不能留白
        return parts.isEmpty() ? "无字段变化" : String.join("; ", parts);
    }

    private static String describeTopic(Long topicId, String topicName) {
        if (topicId == null) {
            return "无";
        }
        if (topicName == null || topicName.isEmpty()) {
            return String.valueOf(topicId);
        }
        return topicName + "(" + topicId + ")";
    }

    // ==================== 小工具 ====================

    /** 读一条待操作的活动；id 不合法或不存在都返回 null（调用方给统一的"活动不存在"） */
    private ApActivity loadForAction(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        return apActivityMapper.selectById(id);
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

    private static String reasonOf(AdminActivitySaveDto dto) {
        return dto == null ? null : reasonOf(dto.getReason());
    }

    private static String reasonOf(String reason) {
        return reason == null ? null : reason.trim();
    }

    /** 解析 {@code yyyy-MM-dd}；格式不合法（含 2026-02-31 这类不存在的日期）返回 null */
    private static Date parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text.trim(), PARSE_DATE);
            return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 格式化日期；null 显示为空串（审计里空串比字面 "null" 好读） */
    private static String fmt(Date date) {
        if (date == null) {
            return "";
        }
        return FORMAT_DATE.format(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
    }

    /**
     * 是否同一天。
     *
     * <p>不直接用 {@code Date.equals}：库里读出来的日期是 JDBC 驱动按某个时区构造的，
     * 与入参解析出来的时刻可能差几毫秒或几小时（同一天但不同时刻）。
     * 直接比毫秒会让"没改日期"被判成"改了"，于是幂等闸口永远拦不住，
     * 每次保存都留下一条"日期 x -> x"的审计记录。按年月日比就没有这个问题。
     */
    private static boolean sameDay(Date a, Date b) {
        if (a == null || b == null) {
            return a == b;
        }
        Calendar ca = Calendar.getInstance();
        ca.setTime(a);
        Calendar cb = Calendar.getInstance();
        cb.setTime(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
            && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeName(String text) {
        return text == null ? "" : text;
    }

    private static String joinCodes(Collection<String> codes) {
        return String.join("/", codes);
    }

    private static int normalizePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    /** 越界时静默回落到默认值：分页参数写错不值当让整个请求失败，但绝不按调用方给的任意值拉数据 */
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

    /**
     * 一次写入的中间结果：归一化后的字段值，或一条参数错误。
     *
     * <p>用一个持有 {@code error} 的小对象代替"抛异常表达参数错误"：参数错误是这条链路上
     * 的常态（用户填错日期），不是异常；而且这里需要的是"返回给对方一句话"，
     * 抛异常会让上面那层 try/catch 把它记成一次失败审计。
     */
    private static final class Prepared {

        private ResponseResult error;

        private String title;
        private String description;
        private String coverImage;
        private String activityType;
        private String category;
        private Date startDate;
        private Date endDate;
        private Long topicId;
        private String topicName;
    }
}
