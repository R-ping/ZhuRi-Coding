package com.zhuri.coding.content.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.admin.AdminAuditSink;
import com.zhuri.coding.content.auth.AdminAuditRecorder;
import com.zhuri.coding.content.mapper.circle.ApCircleHotConfigMapper;
import com.zhuri.coding.content.mapper.circle.ApCircleMapper;
import com.zhuri.coding.content.mapper.topic.TopicMapper;
import com.zhuri.coding.content.service.admin.AdminOpsConfigService;
import com.zhuri.coding.model.admin.pojos.ApAdminAuditLog;
import com.zhuri.coding.model.admin.vos.AdminOpsCircleVO;
import com.zhuri.coding.model.admin.vos.AdminOpsTopicVO;
import com.zhuri.coding.model.circle.pojos.ApCircle;
import com.zhuri.coding.model.circle.pojos.ApCircleHotConfig;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.topic.pojos.ApTopic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运营位配置实现（人气圈子 / 推荐话题）。
 *
 * <p>两处的写法是同构的：<b>读出现状 → 与目标比对 → 整份落位 → 审计留痕</b>。
 * 差别只在"怎么落位"，而这个差别完全来自两张表的形态：
 * <ul>
 *   <li>{@code ap_circle_hot_config.display_order} 上有唯一键 {@code uniq_order}，
 *       所以落位前必须先把旧位次腾空（见 {@link #saveHotCircles}）；</li>
 *   <li>{@code ap_topic.recommend_sort} 上没有约束，落位只要"该变的才写"。</li>
 * </ul>
 *
 * <p><b>为什么"与现状一致"要报错而不是静默成功</b>：运营点保存时若列表没动过，
 * 多半是误操作或页面没刷新。静默成功会让他以为"我刚才改的东西生效了"，
 * 而审计里会多一条与上一条毫无差别的记录，事后根本看不出哪次是真的改过。
 * 报错则明确告诉他"没变化"，这与 {@code AdminContentFoldService} 里
 * "已是折叠状态，请刷新列表"是同一个判断。
 */
@Slf4j
@Service
public class AdminOpsConfigServiceImpl implements AdminOpsConfigService {

    /**
     * 审计对象的 ID。
     *
     * <p>整份清单在审计里是**一个对象**（"人气圈子配置"），不是 5 个独立对象 ——
     * 一次保存是原子动作，拆成 5 条记录反而看不出"这次一共改了什么"。
     * 写 {@code ALL} 而不是拼 id 串：{@code target_id} 只有 64 字符，装不下 20 个话题 id，
     * 具体内容进 {@code detail}（text 列）。
     */
    private static final String TARGET_ID_ALL = "ALL";

    /** detail 摘要上限（列是 text，但摘要不需要长文，超出部分没有信息量） */
    private static final int DETAIL_MAX_LEN = 1000;

    /** is_recommend 取值：推荐 */
    private static final int RECOMMEND_YES = 1;
    /** is_recommend 取值：不推荐 */
    private static final int RECOMMEND_NO = 0;

    /** ap_topic.status 取值：启用（与 C 端 {@code TopicService#recommend} 的过滤条件一致） */
    private static final int TOPIC_STATUS_ENABLED = 1;

    @Autowired
    private ApCircleMapper apCircleMapper;

    @Autowired
    private ApCircleHotConfigMapper apCircleHotConfigMapper;

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private AdminAuditRecorder auditRecorder;

    // ==================== 人气圈子 ====================

    @Override
    public ResponseResult hotCircles() {
        List<ApCircleHotConfig> configs = loadHotConfigs();
        Map<Long, ApCircle> circles = loadCircles(idsOfConfigs(configs));

        List<AdminOpsCircleVO> list = new ArrayList<>();
        for (ApCircleHotConfig config : configs) {
            ApCircle circle = circles.get(config.getCircleId());
            if (circle == null) {
                // 圈子被删了、配置还留着：跳过而不是造一个空壳 —— 名单里出现一行没有名字的东西，
                // 运营只会以为页面坏了。留一条 warn，让人知道库里有条悬挂配置需要清掉。
                log.warn("[AdminOps] 人气位配置引用了不存在的圈子, circleId={}, displayOrder={}",
                    config.getCircleId(), config.getDisplayOrder());
                continue;
            }
            list.add(toCircleVO(circle, config.getDisplayOrder()));
        }
        return ResponseResult.okResult(listData(list));
    }

    @Override
    public ResponseResult searchCircles(String keyword, Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        LambdaQueryWrapper<ApCircle> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ApCircle::getName, keyword.trim());
        }
        // 按成员数降序：运营要找的是"够格摆上人气位"的圈子，人多的先看到。
        // 末尾补 id 作 tie-breaker —— 成员数相同的圈子如果没有确定的次序，
        // 翻页时同一行可能既出现在第 1 页又出现在第 2 页。
        wrapper.orderByDesc(ApCircle::getMemberCount).orderByAsc(ApCircle::getId);

        IPage<ApCircle> result = apCircleMapper.selectPage(new Page<>(p, s), wrapper);
        Map<Long, Integer> orderByCircle = currentCircleOrder();        List<AdminOpsCircleVO> list = result.getRecords().stream()
            .map(c -> toCircleVO(c, orderByCircle.get(c.getId())))
            .collect(Collectors.toList());
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult saveHotCircles(List<Long> circleIds, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_SET_HOT_CIRCLES, TARGET_HOT_CIRCLE, TARGET_ID_ALL, reason);
        try {
            return doSaveHotCircles(circleIds, audit);
        } catch (Exception e) {
            // 失败也留痕：业务事务已标记回滚，这条记录走独立事务（见 AdminAuditRecorder#recordFailure）
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doSaveHotCircles(List<Long> circleIds, ApAdminAuditLog audit) {
        List<Long> ids = normalizeIds(circleIds);
        if (ids == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "人气圈子清单不合法（ID 必须为正整数，且同一圈子不能重复）");
        }
        if (ids.size() > MAX_HOT_CIRCLES) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "人气圈子最多 " + MAX_HOT_CIRCLES + " 个 —— 首页人气位只展示前 " + MAX_HOT_CIRCLES + " 个");
        }

        // 存在性必须先查：配置表没有外键，写进一个不存在的 circle_id 不会报错，
        // 只会在 C 端被静默跳过（hot() 里 circleMap.get() 拿到 null），表现为"配了 5 个只出来 4 个"。
        Map<Long, ApCircle> circles = loadCircles(ids);
        if (circles.size() != ids.size()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "有圈子不存在或已被删除，请刷新候选列表后重试");
        }

        List<ApCircleHotConfig> before = loadHotConfigs();
        List<Long> beforeIds = idsOfConfigs(before);
        if (beforeIds.equals(ids)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "人气圈子配置与当前一致，无需保存");
        }

        relocateHotCircles(before, ids);

        Map<Long, ApCircle> beforeCircles = loadCircles(beforeIds);
        audit.setDetail(truncate("人气圈子 [" + describeHotConfigs(before, beforeCircles)
            + "] -> [" + describeHotIds(ids, circles) + "]"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminOps] 人气圈子配置已更新, before={}, after={}", beforeIds, ids);

        return ResponseResult.okResult(listData(buildHotCircleVOs(ids, circles)));
    }

    /**
     * 把配置表落位到目标顺序（原子动作，调用方必须已在事务中）。
     *
     * <p><b>为什么要分两步</b>：{@code display_order} 上有唯一键 {@code uniq_order}。
     * 直接按目标位次逐行写，遇到"两个圈子互换位次"就会先撞键（A 想写 2，可 2 还被 B 占着）。
     *
     * <p><b>为什么不用「全删再全插」</b>：那样每次保存都会重新分配主键，且两个运营同时保存时，
     * 后一个事务的 INSERT 会撞上前一个刚提交的行、直接报 500。先腾空再落位没有这个问题：
     * 负数区间用 {@code -id} 构造，天然唯一、且与目标区间 1..N 不重叠，
     * 于是"腾空 → 落位"之间不存在任何违反唯一键的中间状态。并发保存的语义退化为
     * "后提交的覆盖先提交的"，这正是配置类操作应有的语义。
     */
    private void relocateHotCircles(List<ApCircleHotConfig> before, List<Long> targetIds) {
        if (!before.isEmpty()) {
            // 负数区间只是"临时停靠"，不会提交（整个方法在事务里，任何一步失败都回滚）
            apCircleHotConfigMapper.update(null,
                new LambdaUpdateWrapper<ApCircleHotConfig>().setSql("display_order = -id"));
        }

        Set<Long> target = new LinkedHashSet<>(targetIds);
        Map<Long, ApCircleHotConfig> existing = before.stream()
            .collect(Collectors.toMap(ApCircleHotConfig::getCircleId, Function.identity(), (a, b) -> a));
        for (ApCircleHotConfig config : before) {
            if (!target.contains(config.getCircleId())) {
                apCircleHotConfigMapper.deleteById(config.getId());
            }
        }

        int order = 1;
        for (Long circleId : targetIds) {
            ApCircleHotConfig current = existing.get(circleId);
            if (current == null) {
                ApCircleHotConfig config = new ApCircleHotConfig();
                config.setCircleId(circleId);
                config.setDisplayOrder(order);
                apCircleHotConfigMapper.insert(config);
            } else {
                apCircleHotConfigMapper.update(null, new LambdaUpdateWrapper<ApCircleHotConfig>()
                    .set(ApCircleHotConfig::getDisplayOrder, order)
                    .eq(ApCircleHotConfig::getId, current.getId()));
            }
            order++;
        }
    }

    // ==================== 推荐话题 ====================

    @Override
    public ResponseResult recommendTopics() {
        List<ApTopic> topics = loadRecommendedTopics();
        // 刻意不按 status 过滤：停用的话题若还挂在推荐位上，C 端是看不见的，
        // 这种"配置里在、页面上不在"的一致性问题只有让它显示出来才能被发现和清掉。
        List<AdminOpsTopicVO> list = topics.stream()
            .map(t -> toTopicVO(t, sortOf(t)))
            .collect(Collectors.toList());
        return ResponseResult.okResult(listData(list));
    }

    @Override
    public ResponseResult searchTopics(String keyword, Integer page, Integer size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);

        LambdaQueryWrapper<ApTopic> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ApTopic::getName, keyword.trim());
        }
        // 同样不按 status 过滤：停用的话题也要能被搜到，否则运营面对一个"我明明想推荐、
        // 却搜不到"的话题无从下手。状态随 VO 返回，由前端标注"已停用"。
        wrapper.orderByDesc(ApTopic::getViewCount).orderByAsc(ApTopic::getId);

        IPage<ApTopic> result = topicMapper.selectPage(new Page<>(p, s), wrapper);
        Map<Long, Integer> orderByTopic = loadRecommendedTopics().stream()
            .collect(Collectors.toMap(ApTopic::getId, AdminOpsConfigServiceImpl::sortOf, (a, b) -> a));
        List<AdminOpsTopicVO> list = result.getRecords().stream()
            .map(t -> toTopicVO(t, orderByTopic.get(t.getId())))
            .collect(Collectors.toList());
        return ResponseResult.okResult(pageData(list, result.getTotal(), p, s));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult saveRecommendTopics(List<Long> topicIds, String reason) {
        ApAdminAuditLog audit = AdminAuditSink.entry(ApAdminAuditLog.MODULE_OPS,
            ACTION_SET_RECOMMEND_TOPICS, TARGET_RECOMMEND_TOPIC, TARGET_ID_ALL, reason);
        try {
            return doSaveRecommendTopics(topicIds, audit);
        } catch (Exception e) {
            auditRecorder.recordFailure(audit, e.getMessage());
            throw e;
        }
    }

    private ResponseResult doSaveRecommendTopics(List<Long> topicIds, ApAdminAuditLog audit) {
        List<Long> ids = normalizeIds(topicIds);
        if (ids == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "推荐话题清单不合法（ID 必须为正整数，且同一话题不能重复）");
        }
        if (ids.size() > MAX_RECOMMEND_TOPICS) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "推荐话题最多 " + MAX_RECOMMEND_TOPICS + " 个");
        }

        List<ApTopic> topics = ids.isEmpty() ? new ArrayList<>() : topicMapper.selectBatchIds(ids);
        if (topics.size() != ids.size()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "有话题不存在或已被删除，请刷新候选列表后重试");
        }
        // 停用的话题放进推荐位是"看着配了、实际不展示" —— C 端查询条件是 status = 1。
        // 直接拒绝并说清原因，比让它安静地待在那里好。
        List<Long> disabled = topics.stream()
            .filter(t -> t.getStatus() == null || t.getStatus() != TOPIC_STATUS_ENABLED)
            .map(ApTopic::getId)
            .collect(Collectors.toList());
        if (!disabled.isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "话题已停用，不能放进推荐位（话题ID：" + join(disabled) + "），请先启用话题");
        }

        List<ApTopic> before = loadRecommendedTopics();
        List<Long> beforeIds = before.stream().map(ApTopic::getId).collect(Collectors.toList());
        // 只比对"顺序"：位次值本身（recommend_sort）不重要，C 端只看相对次序。
        // 因此历史上手工写进去的 0、1 这类非连续位次，只要相对顺序没变就无需重排。
        if (beforeIds.equals(ids)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID,
                "推荐话题配置与当前一致，无需保存");
        }

        relocateRecommendTopics(before, ids);

        Map<Long, String> beforeNames = before.stream()
            .collect(Collectors.toMap(ApTopic::getId, t -> safeName(t.getName()), (a, b) -> a));
        Map<Long, String> afterNames = topics.stream()
            .collect(Collectors.toMap(ApTopic::getId, t -> safeName(t.getName()), (a, b) -> a));
        audit.setDetail(truncate("推荐话题 [" + describeIdsWithOrder(beforeIds, beforeNames)
            + "] -> [" + describeIdsWithOrder(ids, afterNames) + "]"));
        auditRecorder.recordSuccess(audit);
        log.info("[AdminOps] 推荐话题配置已更新, before={}, after={}", beforeIds, ids);

        Map<Long, ApTopic> afterById = topics.stream()
            .collect(Collectors.toMap(ApTopic::getId, Function.identity(), (a, b) -> a));
        List<AdminOpsTopicVO> list = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            list.add(toTopicVO(afterById.get(ids.get(i)), i + 1));
        }
        return ResponseResult.okResult(listData(list));
    }

    /**
     * 把推荐位落位到目标顺序（原子动作，调用方必须已在事务中）。
     *
     * <p>与 {@link #relocateHotCircles} 的差别：{@code recommend_sort} 上没有唯一键，
     * 不需要"腾空"这一步；取而代之的是"只写变化的行" ——
     * 整份重写会把没动过的话题的 {@code updated_at} 也刷新一遍（该列是
     * {@code ON UPDATE CURRENT_TIMESTAMP}），而按更新时间排序的地方就会莫名其妙地变序。
     */
    private void relocateRecommendTopics(List<ApTopic> before, List<Long> targetIds) {
        Set<Long> target = new LinkedHashSet<>(targetIds);

        // 1) 下推荐位：只动"当前在推荐位、且不在目标清单里"的行
        LambdaUpdateWrapper<ApTopic> off = new LambdaUpdateWrapper<ApTopic>()
            .set(ApTopic::getIsRecommend, RECOMMEND_NO)
            .eq(ApTopic::getIsRecommend, RECOMMEND_YES);
        if (!target.isEmpty()) {
            off.notIn(ApTopic::getId, target);
        }
        topicMapper.update(null, off);
        // 注：下位时不清 recommend_sort。C 端以 is_recommend 为过滤条件，
        // 残留的旧位次不会被读到；清掉反而要多写一列、多一次刷新 updated_at 的风险。

        // 2) 上推荐位 / 调位次：位次已经正确的行不动
        Map<Long, Integer> orderBefore = before.stream()
            .collect(Collectors.toMap(ApTopic::getId, AdminOpsConfigServiceImpl::sortOf, (a, b) -> a));
        int order = 1;
        for (Long topicId : targetIds) {
            Integer current = orderBefore.get(topicId);
            if (current != null && current == order) {
                order++;
                continue;
            }
            topicMapper.update(null, new LambdaUpdateWrapper<ApTopic>()
                .set(ApTopic::getIsRecommend, RECOMMEND_YES)
                .set(ApTopic::getRecommendSort, order)
                .eq(ApTopic::getId, topicId));
            order++;
        }
    }

    // ==================== 读工具 ====================

    private List<ApCircleHotConfig> loadHotConfigs() {
        // 位次上有唯一键，正常只会有 5 行；补 id 排序只是为了在上限被临时放开时也有确定次序
        return apCircleHotConfigMapper.selectList(new LambdaQueryWrapper<ApCircleHotConfig>()
            .orderByAsc(ApCircleHotConfig::getDisplayOrder)
            .orderByAsc(ApCircleHotConfig::getId));
    }

    private Map<Long, Integer> currentCircleOrder() {
        return loadHotConfigs().stream().collect(Collectors.toMap(
            ApCircleHotConfig::getCircleId, ApCircleHotConfig::getDisplayOrder, (a, b) -> a));
    }

    /**
     * 按 id 批量取圈子。
     *
     * <p>空集合直接返回空 Map，不去打库 —— {@code selectBatchIds} 收到空集合会拼出
     * {@code WHERE id IN ()} 这种非法 SQL。用 {@code Collection} 而不是 {@code List}，
     * 是为了让调用方不必先判断"有没有货"。
     */
    private Map<Long, ApCircle> loadCircles(Collection<Long> circleIds) {
        if (circleIds == null || circleIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return apCircleMapper.selectBatchIds(circleIds).stream()
            .collect(Collectors.toMap(ApCircle::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 当前挂在推荐位上的话题（按位次）。
     *
     * <p><b>刻意不带 {@code status} 过滤</b>：C 端的条件是 {@code is_recommend = 1 AND status = 1}，
     * 但如果后台也照抄这个条件，一个被停用的话题就会从配置清单里"消失" ——
     * 运营看到的是"清单里没有它"，而数据库里它一直占着一个位次。两边看到的必须是同一份清单。
     */
    private List<ApTopic> loadRecommendedTopics() {
        return topicMapper.selectList(new LambdaQueryWrapper<ApTopic>()
            .eq(ApTopic::getIsRecommend, RECOMMEND_YES)
            .orderByAsc(ApTopic::getRecommendSort)
            .orderByAsc(ApTopic::getId));
    }

    // ==================== VO / 出参工具 ====================

    private AdminOpsCircleVO toCircleVO(ApCircle circle, Integer displayOrder) {
        AdminOpsCircleVO vo = new AdminOpsCircleVO();
        vo.setCircleId(circle.getId());
        vo.setName(safeName(circle.getName()));
        vo.setMemberCount(circle.getMemberCount() == null ? 0 : circle.getMemberCount());
        vo.setPinsCount(circle.getPinsCount() == null ? 0 : circle.getPinsCount());
        vo.setDisplayOrder(displayOrder);
        return vo;
    }

    private AdminOpsTopicVO toTopicVO(ApTopic topic, Integer recommendOrder) {
        AdminOpsTopicVO vo = new AdminOpsTopicVO();
        vo.setTopicId(topic.getId());
        vo.setName(safeName(topic.getName()));
        vo.setBadge(topic.getBadge() == null ? "" : topic.getBadge());
        vo.setStatus(topic.getStatus());
        vo.setParticipantCount(topic.getParticipantCount() == null ? 0L : topic.getParticipantCount());
        vo.setViewCount(topic.getViewCount() == null ? 0L : topic.getViewCount());
        vo.setRecommendOrder(recommendOrder);
        return vo;
    }

    private List<AdminOpsCircleVO> buildHotCircleVOs(List<Long> ids, Map<Long, ApCircle> circles) {
        List<AdminOpsCircleVO> list = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            list.add(toCircleVO(circles.get(ids.get(i)), i + 1));
        }
        return list;
    }

    private Map<String, Object> listData(List<?> list) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", list.size());
        return data;
    }

    private Map<String, Object> pageData(List<?> list, long total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private int normalizePage(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    /**
     * 单页条数收口：人工挑选场景不需要一次拉几百条，同时拦住前端误传的 {@code size=100000}。
     * 越界时静默回落到默认值而不是报错 —— 分页参数写错不值当让整个请求失败，
     * 但"服务端绝不按调用方给的任意值拉数据"这条必须守住。
     */
    private int normalizeSize(Integer size) {
        return (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;
    }

    /**
     * 校验并复制有序 id 清单。
     *
     * <p>这一层是数据完整性的最后一道闸口，所以它放在服务里而不是只在控制器里：
     * 控制器校验挡的是"这个 HTTP 请求合不合规矩"，服务要挡的是"这份清单能不能写进库" ——
     * 后者对任何调用方都成立。重复 id 必须在这里拦住：配置表对 {@code circle_id} 没有唯一约束，
     * 重复提交会真的插出两行，然后在 C 端表现为"人气圈子里同一个圈子出现两次"。
     *
     * @return 归一化后的清单；{@code null} 表示校验未通过
     */
    private static List<Long> normalizeIds(List<Long> raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(raw.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id == null || id <= 0 || !seen.add(id)) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    private static List<Long> idsOfConfigs(List<ApCircleHotConfig> configs) {
        return configs.stream().map(ApCircleHotConfig::getCircleId).collect(Collectors.toList());
    }

    private static String safeName(String name) {
        return name == null ? "" : name;
    }

    /**
     * 取推荐位次，把 {@code null} 归一为 0。
     *
     * <p>列上虽然写了 {@code DEFAULT 0}，但它是可空的 —— 一次绕过 ORM 的写入就可能留下 NULL。
     * 不归一的话，{@code Collectors.toMap} 会直接 NPE（Map 不接受 null value），
     * 而那是个"某个话题数据脏了、整个运营位页面打不开"的放大效应。
     */
    private static Integer sortOf(ApTopic topic) {
        return topic.getRecommendSort() == null ? 0 : topic.getRecommendSort();
    }

    /** 审计摘要：{@code 1:前端圈, 2:后端圈}；空清单显示"空"而不是留白（留白看起来像字段没填） */
    private static String describeHotConfigs(List<ApCircleHotConfig> configs, Map<Long, ApCircle> circles) {
        if (configs.isEmpty()) {
            return "空";
        }
        return configs.stream()
            .map(c -> c.getDisplayOrder() + ":" + describeCircle(c.getCircleId(), circles))
            .collect(Collectors.joining(", "));
    }

    /** 审计摘要（目标清单侧）：位次由数组下标推出 */
    private static String describeHotIds(List<Long> ids, Map<Long, ApCircle> circles) {
        return describeIdsWithOrder(ids, ids.stream()
            .collect(Collectors.toMap(Function.identity(),
                id -> describeCircle(id, circles), (a, b) -> a, LinkedHashMap::new)));
    }

    private static String describeCircle(Long circleId, Map<Long, ApCircle> circles) {
        ApCircle circle = circles.get(circleId);
        // 取不到名字就退回 id：审计宁可难看，不能缺信息
        return circle == null ? String.valueOf(circleId) : safeName(circle.getName());
    }

    private static String describeIdsWithOrder(List<Long> ids, Map<Long, String> names) {
        if (ids.isEmpty()) {
            return "空";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            String name = names.get(id);
            parts.add((i + 1) + ":" + (name == null || name.isEmpty() ? String.valueOf(id) : name));
        }
        return String.join(", ", parts);
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= DETAIL_MAX_LEN) {
            return text;
        }
        return text.substring(0, DETAIL_MAX_LEN);
    }
}
