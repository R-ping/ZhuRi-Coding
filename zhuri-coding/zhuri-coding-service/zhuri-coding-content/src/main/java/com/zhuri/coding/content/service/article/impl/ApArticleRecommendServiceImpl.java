package com.zhuri.coding.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.interaction.ApArticleExposureMapper;
import com.zhuri.coding.content.mapper.interaction.ApBehaviorLikesMapper;
import com.zhuri.coding.content.mapper.interaction.ApBrowseHistoryMapper;
import com.zhuri.coding.content.mapper.interaction.ApCollectionMapper;
import com.zhuri.coding.model.behavior.pojos.ApArticleExposure;
import com.zhuri.coding.content.service.article.ApArticleRecommendService;
import com.zhuri.coding.model.article.dtos.ArticleInteractionCountDTO;
import com.zhuri.coding.model.article.dtos.ArticleRecommendDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.pojos.ApBehaviorLikes;
import com.zhuri.coding.model.behavior.pojos.ApBrowseHistory;
import com.zhuri.coding.model.behavior.pojos.ApCollection;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApArticleRecommendServiceImpl implements ApArticleRecommendService {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;
    private static final String UNTAGGED = "__untagged__";
    private static final String SUB_TAB_RECOMMEND = "recommend";
    private static final String SUB_TAB_LATEST = "latest";
    /** 兴趣画像缓存 key 前缀 */
    private static final String INTEREST_CACHE_KEY = "recommend:interest:";

    /** 更新回流出处 Redis key 前缀：记录每篇文章最近一次"更新回流"的时间，30 天内不重复触发 */
    private static final String REFRESH_CACHE_KEY = "recommend:update-refresh:";

    /** 兴趣画像行为折算权重：浏览 */
    private static final double WEIGHT_BROWSE = 1.0;
    /** 兴趣画像行为折算权重：点赞 */
    private static final double WEIGHT_LIKE = 3.0;
    /** 兴趣画像行为折算权重：收藏 */
    private static final double WEIGHT_COLLECT = 4.0;

    /** 候选池上限（性能兜底） */
    @Value("${recommend.max-candidates:2000}")
    private int maxCandidates;

    /** 候选时间窗口（天），取最近 windowDays 天内发布或被实质更新的文章 */
    @Value("${recommend.window-days:7}")
    private int windowDays;

    /** 更新回流占比上限（0~1）：仅对"因更新进入候选池"的文章生效，新发布文章不受限 */
    @Value("${recommend.update-article-ratio:0.3}")
    private double updateArticleRatio;

    /** 同一篇文章两次"更新回流"的最小间隔天数，防止反复微调反复曝光（与 F1 的 24 小时更新记录限制叠加使用） */
    @Value("${recommend.refresh-interval-days:30}")
    private int refreshIntervalDays;

    /** 多样性配额：全局推荐序列中同一标签最多出现的篇数 */
    @Value("${recommend.max-per-tag:2}")
    private int maxPerTag;

    /** 作者上限：全局推荐序列中同一作者最多出现的篇数，0 表示不限制 */
    @Value("${recommend.max-per-author:3}")
    private int maxPerAuthor;

    /** 无标签文章归入的桶 */
    @Value("${recommend.untagged-bucket:__untagged__}")
    private String untaggedBucket;

    /** 排序随机抖动幅度（±），与 seed 绑定，确保刷新出新内容又不破坏同 seed 分页连续性 */
    @Value("${recommend.seed-jitter:0.15}")
    private double seedJitter;

    /** 兴趣匹配的最大加成（0~1 分数段），用于用户个性化加权 */
    @Value("${recommend.interest-boost-max:0.30}")
    private double interestBoostMax;

    /** 构建兴趣画像时最多采样的最近浏览条数 */
    @Value("${recommend.interest-browse-sample:100}")
    private int interestBrowseSample;

    /** 兴趣画像缓存 TTL（秒），默认 30 分钟 */
    @Value("${recommend.interest-cache-ttl:1800}")
    private long interestCacheTtlSeconds;

    // ==================== 基础评分权重（可配置，便于 A/B 调优） ====================

    /** 编辑/系统热度分权重 */
    @Value("${recommend.weight.score:0.25}")
    private double weightScore;
    /** 时效性权重 */
    @Value("${recommend.weight.recency:0.20}")
    private double weightRecency;
    /** 阅读量权重 */
    @Value("${recommend.weight.views:0.15}")
    private double weightViews;
    /** 点赞量权重 */
    @Value("${recommend.weight.likes:0.15}")
    private double weightLikes;
    /** 评论量权重 */
    @Value("${recommend.weight.comments:0.10}")
    private double weightComments;
    /** 收藏量权重 */
    @Value("${recommend.weight.collects:0.10}")
    private double weightCollects;
    /** 热度分归一化除数（越高，编辑热度影响越小） */
    @Value("${recommend.score-normalize-max:10000}")
    private int scoreNormalizeMax;
    /** 时效线性衰减窗口（天） */
    @Value("${recommend.recency-window-days:7}")
    private int recencyWindowDays;

    // ==================== 服务端已读去重 ====================

    /** 参与已读去重的最近浏览条数上限（控制 NOT IN 规模） */
    @Value("${recommend.exclude-read-count:500}")
    private int excludeReadCount;
    /** 已读去重只考虑该天数内的浏览记录 */
    @Value("${recommend.exclude-read-window-days:7}")
    private int excludeReadWindowDays;

    // ==================== 数据回流闭环（近期互动热度） ====================

    /** 近期互动热度权重 */
    @Value("${recommend.interaction-boost-max:0.20}")
    private double interactionBoostMax;
    /** 统计近期互动的秒数窗口（默认 24 小时） */
    @Value("${recommend.interaction-window-seconds:86400}")
    private long interactionWindowSeconds;
    /** 近期互动热度缓存 TTL（秒），默认 5 分钟 */
    @Value("${recommend.interaction-cache-ttl:300}")
    private long interactionCacheTtlSeconds;

    // ==================== 曝光/行为闭环（负反馈） ====================

    /** 只看该天数内对该用户的曝光记录 */
    @Value("${recommend.exposure-window-days:3}")
    private int exposureWindowDays;

    /** 曝光而未消费的最大降权幅度（分数段内） */
    @Value("${recommend.exposure-penalty-max:0.10}")
    private double exposurePenaltyMax;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private ApBrowseHistoryMapper apBrowseHistoryMapper;

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private ApArticleExposureMapper apArticleExposureMapper;

    /** Redisson 客户端，用于兴趣画像缓存；缺失/异常时优雅降级为直接计算 */
    @Autowired(required = false)
    private org.redisson.api.RedissonClient redissonClient;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public ResponseResult recommend(ArticleRecommendDto dto) {
        // 兼容旧端点：等价于推荐分栏的全站推荐
        return doRecommend(dto, "all");
    }

    @Override
    public ResponseResult recommendAll(ArticleRecommendDto dto) {
        return doRecommend(dto, "all");
    }

    @Override
    public ResponseResult recommendFollow(ArticleRecommendDto dto) {
        return doRecommend(dto, "follow");
    }

    @Override
    public ResponseResult recommendCate(ArticleRecommendDto dto) {
        return doRecommend(dto, "cate");
    }

    /**
     * 统一的推荐入口核心逻辑。
     * <p>
     * 三个入口（综合/关注/分类）通过 type 区分语义与数据来源，实现流量分流：
     * <ul>
     *   <li>type=all    综合频道：全站候选，channel 固定为 __all__</li>
     *   <li>type=cate   分类频道：channel 指定具体频道ID</li>
     *   <li>type=follow 关注分栏：仅查询当前登录用户所关注作者的已发布文章</li>
     * </ul>
     * subTab 区分分栏：
     * <ul>
     *   <li>recommend 推荐分栏：评分加权（基础分 + 兴趣加成）并叠加 seed 随机抖动 + 配额贪心，产出横向覆盖序列</li>
     *   <li>latest    最新分栏：按发布时间倒序，SQL 偏移分页</li>
     * </ul>
     * seed 语义（支持“刷新出新内容”）：
     * <ul>
     *   <li>刷新/首次加载 不带 seed → 服务端生成新 seed，抖动不同 → 推荐内容随之变化</li>
     *   <li>向下分页 回传服务端下发的 seed → 同 seed 抖动恒定 → 排序跨页连续</li>
     * </ul>
     * 个性化：登录用户按 浏览/点赞/收藏 提炼兴趣标签并对命中内容加成；
     * 匿名用户跳过个性化，仅保留 seed 抖动。
     */
    private ResponseResult doRecommend(ArticleRecommendDto dto, String type) {
        int size = (dto.getSize() == null || dto.getSize() <= 0) ? DEFAULT_SIZE : Math.min(dto.getSize(), MAX_SIZE);
        int page = (dto.getPage() == null || dto.getPage() < 0) ? 0 : dto.getPage();
        String channel = (dto.getChannel() == null || dto.getChannel().isEmpty()) ? "__all__" : dto.getChannel();
        String subTab = (dto.getSubTab() == null || dto.getSubTab().isEmpty()) ? SUB_TAB_RECOMMEND : dto.getSubTab();

        // seed 作为会话/分页锚点。配额算法为确定性输出，seed 仅用于保持前端分页协议一致
        long seed = (dto.getSeed() != null) ? dto.getSeed() : System.currentTimeMillis();

        // 关注分栏：先解析当前登录用户关注的作者ID集合
        List<Integer> followAuthorIds = null;
        if ("follow".equals(type)) {
            ApUser user = AppThreadLocalUtil.getUser();
            if (user == null || user.getId() == null) {
                log.info("RecommendFollow: 未登录用户，返回空列表");
                return ResponseResult.okResult(buildEmptyResponse(seed, page, size));
            }
            followAuthorIds = apFollowMapper.selectList(
                    new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getUserId, user.getId()))
                    .stream()
                    .map(ApFollow::getFollowUserId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (followAuthorIds.isEmpty()) {
                log.info("RecommendFollow: 用户 {} 未关注任何作者，返回空列表", user.getId());
                return ResponseResult.okResult(buildEmptyResponse(seed, page, size));
            }
        }

        // 解析频道ID（综合/分类入口；关注分栏不按频道过滤）
        Integer channelId = null;
        if (!"follow".equals(type) && !"__all__".equals(channel)) {
            try { channelId = Integer.parseInt(channel); } catch (NumberFormatException ignored) {}
        }

        // 最新分栏：按发布时间倒序，SQL 偏移分页
        if (SUB_TAB_LATEST.equals(subTab)) {
            return loadLatest(dto, size, page, seed, channelId, followAuthorIds, type);
        }

        // 推荐分栏：查询候选池（时间窗口内，评分优先），并按 excludeIds 排除已读/已展示内容
        // 已读去重来源 = 前端上报 excludeIds ∪ 服务端近 N 天浏览历史（主动去重，避免重复推荐）
        ApUser currentUser = AppThreadLocalUtil.getUser();
        List<Long> excludeIds = (dto.getExcludeIds() == null)
                ? new ArrayList<>() : new ArrayList<>(dto.getExcludeIds());
        if (currentUser != null && currentUser.getId() != null) {
            List<Long> serverRead = resolveServerReadIds(currentUser.getId());
            for (Long id : serverRead) {
                if (!excludeIds.contains(id)) {
                    excludeIds.add(id);
                }
            }
        }
        // 空集合转 null，避免 SQL 出现无效的 NOT IN ()
        if (excludeIds.isEmpty()) {
            excludeIds = null;
        }
        List<ApArticle> candidates;
        if ("follow".equals(type)) {
            candidates = apArticleMapper.selectRecommendCandidatesByAuthors(followAuthorIds, maxCandidates, windowDays, excludeIds);
        } else {
            candidates = apArticleMapper.selectRecommendCandidates(channelId, maxCandidates, dto.getTagName(), windowDays, excludeIds);
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("Recommend: no candidates for channel={}, windowDays={}", channel, windowDays);
            return ResponseResult.okResult(buildEmptyResponse(seed, page, size));
        }

        // F3 更新回流：识别"因更新进入候选池"的文章（发布超窗但近期被实质更新），
        // 并对其做 30 天回流去重——冷却期内的文章从候选池剔除，避免反复微调反复曝光。
        Date windowStart = new Date(System.currentTimeMillis() - windowDays * 86400000L);
        Set<Long> eligibleRefreshIds = resolveEligibleRefreshIds(candidates, windowStart);

        // 个性化画像：从登录用户的行为（浏览/点赞/收藏）提炼兴趣标签权重；匿名用户不做个性化
        Map<String, Double> interestWeights = (currentUser != null && currentUser.getId() != null)
                ? getInterestWeights(currentUser.getId())
                : Collections.emptyMap();
        double interestWeightMax = interestWeights.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);

        // 2. 计算各项指标的最大值（用于对数归一化）—— 单次遍历
        long now = System.currentTimeMillis();
        int maxViews = 0, maxLikes = 0, maxComments = 0, maxCollections = 0;
        for (ApArticle a : candidates) {
            maxViews = Math.max(maxViews, a.getViews() != null ? a.getViews() : 0);
            maxLikes = Math.max(maxLikes, a.getLikes() != null ? a.getLikes() : 0);
            maxComments = Math.max(maxComments, a.getComment() != null ? a.getComment() : 0);
            maxCollections = Math.max(maxCollections, a.getCollection() != null ? a.getCollection() : 0);
        }

        // 3. 预计算最终加权分数（基础分 + 兴趣加成）并叠加 seed 随机抖动，按分数降序
        //    数据回流：近期阅读数作为「正反馈」热度信号反哺排序；
        //            近期曝光而未消费作为「负反馈」信号降权，避免反复推荐用户不感兴趣的内容。
        Map<Long, Integer> interactionCounts = loadRecentInteractionCounts(candidates);
        int maxInteraction = interactionCounts.values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);
        // 登录用户：加载其对候选文章的近期曝光次数，用于负反馈降权
        Map<Long, Integer> userExposureCounts = (currentUser != null && currentUser.getId() != null)
                ? loadUserExposureCounts(candidates, currentUser.getId())
                : Collections.emptyMap();
        int maxExposure = userExposureCounts.values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);
        Map<Long, Double> scoreCache = new HashMap<>();
        for (ApArticle article : candidates) {
            double base = computeBaseScore(article, now, maxViews, maxLikes, maxComments, maxCollections,
                    maxInteraction, interactionCounts);
            double interest = interestBoost(interestWeights, interestWeightMax, article);
            double jitter = seededJitter(seed, article.getId());
            double exposurePenalty = exposurePenalty(userExposureCounts, maxExposure, article.getId());
            scoreCache.put(article.getId(), base + interest + jitter - exposurePenalty);
        }
        candidates.sort((a, b) -> Double.compare(scoreCache.get(b.getId()), scoreCache.get(a.getId())));

        // 4. 全局配额贪心：标签配额 + 作者上限 + 更新回流占比上限，产出横向覆盖的全局序列（跨页稳定）
        List<ApArticle> globalSequence = buildGlobalSequence(candidates, eligibleRefreshIds);

        // F3 回流戳记：仅为真正进入推荐序列的更新回流文章落戳（30 天内不再重复触发），
        // 只在原本配额内新增一次 Redis 写；Redis 不可用/异常时忽略，不影响主流程。
        recordRefresh(globalSequence, eligibleRefreshIds);

        // 5. 分页截取
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, globalSequence.size());
        boolean hasMore = toIndex < globalSequence.size();
        List<ApArticle> pageResult = fromIndex < globalSequence.size()
                ? globalSequence.subList(fromIndex, toIndex)
                : Collections.emptyList();

        // 记录本页曝光（负反馈闭环的输入；最佳努力，失败不影响主流程）
        recordExposure(currentUser, channel, page, pageResult, seed);

        // 6. 构建响应 - null-safe 处理
        List<Map<String, Object>> safeList = pageResult.stream()
                .map(ApArticle::nullSafeToMap).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("list", safeList);
        result.put("seed", seed);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", hasMore);
        result.put("total", globalSequence.size());

        log.info("Recommend: candidates={}, seq={}, returned {} articles, hasMore={}, seed={}, channel={}, page={}, type={}, subTab={}",
                candidates.size(), globalSequence.size(), pageResult.size(), hasMore, seed, channel, page, type, subTab);
        return ResponseResult.okResult(result);
    }

    /**
     * 最新分栏：按发布时间倒序查询，SQL 偏移分页。
     * 多取 size+1 条用于探测是否还有更多，避免额外的 count 查询。
     */
    private ResponseResult loadLatest(ArticleRecommendDto dto, int size, int page, long seed,
                                      Integer channelId, List<Integer> authorIds, String type) {
        int limit = size + 1;
        int offset = page * size;
        List<ApArticle> list = apArticleMapper.selectLatestArticles(channelId, dto.getTagName(), authorIds, offset, limit);
        boolean hasMore = list != null && list.size() > size;
        List<ApArticle> pageList = (hasMore && list != null) ? list.subList(0, size)
                : (list != null ? list : Collections.emptyList());

        // 真实总数：仅当本页有数据时额外一次 count（复用同一过滤条件），空结果免查询
        long totalCount = 0;
        if (list != null && !list.isEmpty()) {
            Long c = apArticleMapper.countLatestArticles(channelId, dto.getTagName(), authorIds);
            totalCount = c != null ? c : 0;
        }

        List<Map<String, Object>> safeList = pageList.stream()
                .map(ApArticle::nullSafeToMap).collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("list", safeList);
        result.put("seed", seed);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", hasMore);
        result.put("total", totalCount);

        log.info("RecommendLatest: returned {} articles, hasMore={}, channel={}, page={}, type={}",
                safeList.size(), hasMore, channelId, page, type);
        return ResponseResult.okResult(result);
    }

    /**
     * 全局配额贪心：对「评分降序」的候选做一次遍历，
     * 同一标签累计不超过 maxPerTag、同一作者累计不超过 maxPerAuthor，
     * 更新回流文章（eligibleRefreshIds）累计不超过 {@link #computeUpdateCap} 计算的占比上限，
     * 产出跨页稳定的全局推荐序列。
     * 配额计数全局累计（跨页），保证横向覆盖，避免「本页与下页同标签」。
     * 若严格配额后序列不足候选总数（标签过于集中），放开配额按评分追加补齐，保证列表可填满。
     *
     * @param eligibleRefreshIds 更新回流文章ID集合（已通过 30 天回流去重）；空集合表示无更新回流约束
     */
    private List<ApArticle> buildGlobalSequence(List<ApArticle> candidates, Set<Long> eligibleRefreshIds) {
        Map<String, Integer> tagCount = new HashMap<>();
        Map<Long, Integer> authorCount = new HashMap<>();
        List<ApArticle> seq = new ArrayList<>(candidates.size());

        int updateCap = computeUpdateCap(candidates, eligibleRefreshIds);
        int updateUsed = 0;

        for (ApArticle art : candidates) {
            boolean isUpdateRefresh = eligibleRefreshIds.contains(art.getId());
            if (isUpdateRefresh && updateUsed >= updateCap) {
                continue; // 更新回流占比上限已满 → 跳过，避免挤占新发布文章曝光
            }
            String mainTag = resolveMainTag(art);
            if (tagCount.getOrDefault(mainTag, 0) >= maxPerTag) {
                continue; // 标签配额已满 → 跳过
            }
            if (maxPerAuthor > 0 && art.getAuthorId() != null
                    && authorCount.getOrDefault(art.getAuthorId(), 0) >= maxPerAuthor) {
                continue; // 作者上限已满 → 跳过
            }
            seq.add(art);
            if (isUpdateRefresh) {
                updateUsed++;
            }
            tagCount.put(mainTag, tagCount.getOrDefault(mainTag, 0) + 1);
            if (art.getAuthorId() != null) {
                authorCount.put(art.getAuthorId(), authorCount.getOrDefault(art.getAuthorId(), 0) + 1);
            }
        }

        // 配额不足降级：候选标签集中导致序列过短，按评分追加剩余候选补齐（更新回流上限仍未突破）
        if (seq.size() < candidates.size()) {
            Set<Long> seen = seq.stream().map(ApArticle::getId).collect(Collectors.toSet());
            for (ApArticle art : candidates) {
                if (seen.contains(art.getId())) {
                    continue;
                }
                boolean isUpdateRefresh = eligibleRefreshIds.contains(art.getId());
                if (isUpdateRefresh && updateUsed >= updateCap) {
                    continue; // 候选都来自更新回流且已占满上限 → 继续守上限
                }
                seq.add(art);
                if (isUpdateRefresh) {
                    updateUsed++;
                }
            }
        }
        return seq;
    }

    /**
     * 计算更新回流文章在推荐序列中的占比上限。
     * <ul>
     *   <li>上限 = 候选池数量 × updateArticleRatio（至少 1），对标「更新文章在候选池中占比 ≤30%」；</li>
     *   <li>候选池中完全没有「非更新」内容（newCount=0）时放开上限，保证推荐流不空；</li>
     *   <li>updateArticleRatio ≤ 0 视为不启用占比约束，全部放行。</li>
     * </ul>
     */
    private int computeUpdateCap(List<ApArticle> candidates, Set<Long> eligibleRefreshIds) {
        if (candidates == null || candidates.isEmpty()
                || eligibleRefreshIds == null || eligibleRefreshIds.isEmpty()) {
            return 0;
        }
        int size = candidates.size();
        long newCount = candidates.stream()
                .filter(a -> !eligibleRefreshIds.contains(a.getId())).count();
        if (newCount <= 0) {
            return eligibleRefreshIds.size(); // 候选池全为更新回流 → 放开上限保证推荐流不空
        }
        if (updateArticleRatio <= 0) {
            return eligibleRefreshIds.size();
        }
        return Math.max(1, (int) Math.round(size * updateArticleRatio));
    }

    /**
     * 判断文章是否为「因更新进入候选池」的更新回流文章：
     * 发布时间已超出候选窗口（非正常新发布），但存在晚于发布的实质更新、且更新时间落在窗口内。
     * 仅这类文章计入更新回流占比上限；新发布文章（publish_time 在窗口内）不受此限。
     */
    private boolean isUpdateRefreshed(ApArticle article, Date windowStart) {
        Date p = article.getPublishTime();
        Date u = article.getUpdateTime();
        if (p == null || u == null || !u.after(p)) {
            return false; // 无发布时间 / 无实质更新
        }
        if (!p.before(windowStart)) {
            return false; // 发布时间仍在窗口内 → 属正常新发布，不占更新回流比例
        }
        return u.after(windowStart); // 发布时间超窗、更新在窗内 → 因更新回流
    }

    /**
     * 解析本次更新回流文章并执行 30 天回流去重：
     * 对「因更新进入候选池」的文章，若其 Redis 回流戳记仍处于冷却期（{@link #refreshIntervalDays} 天内），
     * 从候选池剔除；其余记入合格集合供占比上限与回流戳记写入使用。
     * <p>Redisson 未装配/异常时 fail-open：不剔除、均可回流，仅退化为占比上限约束。</p>
     *
     * @return 本次允许回流的文章ID集合（可能为空）
     */
    private Set<Long> resolveEligibleRefreshIds(List<ApArticle> candidates, Date windowStart) {
        Set<Long> eligible = new HashSet<>();
        if (candidates == null || candidates.isEmpty()) {
            return eligible;
        }
        Iterator<ApArticle> it = candidates.iterator();
        while (it.hasNext()) {
            ApArticle a = it.next();
            Long id = a.getId();
            if (id == null || !isUpdateRefreshed(a, windowStart)) {
                continue;
            }
            if (refreshInCooldown(id)) {
                it.remove(); // 30 天内已回流过 → 本次不再因更新回流
            } else {
                eligible.add(id);
            }
        }
        return eligible;
    }

    /** 判断某文章是否处于更新回流冷却期（Redis 戳记存在即冷却中）；失败 fail-open 为可回流 */
    private boolean refreshInCooldown(Long articleId) {
        if (redissonClient == null || articleId == null) {
            return false;
        }
        try {
            String v = (String) redissonClient.getBucket(REFRESH_CACHE_KEY + articleId).get();
            return v != null && !v.isEmpty();
        } catch (Exception e) {
            log.warn("读取更新回流冷却戳记失败，按可回流处理 articleId={}", articleId, e);
            return false;
        }
    }

    /** 为真正进入推荐序列的更新回流文章写入回流戳记（TTL = refreshIntervalDays 天）；失败忽略不影响主流程 */
    private void recordRefresh(List<ApArticle> sequence, Set<Long> eligibleRefreshIds) {
        if (redissonClient == null || sequence == null || eligibleRefreshIds == null || eligibleRefreshIds.isEmpty()) {
            return;
        }
        try {
            for (ApArticle art : sequence) {
                Long id = art.getId();
                if (id != null && eligibleRefreshIds.contains(id)) {
                    redissonClient.getBucket(REFRESH_CACHE_KEY + id)
                            .set(new Date().getTime(), refreshIntervalDays, TimeUnit.DAYS);
                }
            }
        } catch (Exception e) {
            log.warn("写入更新回流戳记失败，忽略（不影响推荐主流程）", e);
        }
    }

    /**
     * 文章的最后时效时间：有实质更新（update_time 晚于 publish_time）取 update_time，否则取 publish_time。
     * 作为时效衰减（computeBaseScore）与候选排序（COALESCE(update_time, publish_time)）的基准。
     */
    private Date lastEffectiveTime(ApArticle article) {
        Date u = article.getUpdateTime();
        Date p = article.getPublishTime();
        if (u != null && p != null && u.after(p)) {
            return u;
        }
        return p;
    }

    /**
     * 取文章主标签（第一个），无标签文章归入 untaggedBucket。
     */
    private String resolveMainTag(ApArticle article) {
        if (article.getTags() != null && !article.getTags().isEmpty()
                && article.getTags().get(0) != null && !article.getTags().get(0).trim().isEmpty()) {
            return article.getTags().get(0).trim();
        }
        return (untaggedBucket == null || untaggedBucket.isEmpty()) ? UNTAGGED : untaggedBucket;
    }

    /**
     * 计算基础加权推荐分数。
     * 权重可通过配置项 recommend.weight.* 调整（默认：score×0.25 + recency×0.20 + views×0.15
     * + likes×0.15 + comments×0.10 + collects×0.10），便于后续 A/B 调优。
     * 互动指标采用对数归一化 log(1+x)/log(1+max)，弱化爆款数值的线性压制，让长尾内容有生存空间。
     * 另叠加「近期互动热度」boost（见 {@link #recentInteractionBoost}），让最近真实的浏览/阅读行为反哺排序。
     */
    private double computeBaseScore(ApArticle article, long now,
                                    int maxViews, int maxLikes, int maxComments, int maxCollections,
                                    int maxInteraction, Map<Long, Integer> interactionCounts) {
        // 热度分（归一化到0-1）
        int score = article.getScore() != null ? article.getScore() : 0;
        double normalizedScore = scoreNormalizeMax > 0
                ? Math.min((double) score / scoreNormalizeMax, 1.0) : 0;

        // 时效性因子：recencyWindowDays 天内线性衰减，0=最旧/超窗，1=刚刚（发布或更新）。
        // F3 更新回流：衰减基准从发布时间改为「最后时效时间」（见 lastEffectiveTime），
        // 使近期被实质更新的旧文章焕发时效优势，而非继续按原始发布时间老化。
        double recencyFactor = 0;
        if (recencyWindowDays > 0) {
            Date effective = lastEffectiveTime(article);
            if (effective != null) {
                long daysSince = (now - effective.getTime()) / (1000L * 60 * 60 * 24);
                recencyFactor = Math.max(0, 1.0 - daysSince / (double) recencyWindowDays);
            }
        }

        // 用户互动指标（对数归一化到0-1）
        int views = article.getViews() != null ? article.getViews() : 0;
        int likes = article.getLikes() != null ? article.getLikes() : 0;
        int comments = article.getComment() != null ? article.getComment() : 0;
        int collections = article.getCollection() != null ? article.getCollection() : 0;

        double logViews = logNorm(views, maxViews);
        double logLikes = logNorm(likes, maxLikes);
        double logComments = logNorm(comments, maxComments);
        double logCollections = logNorm(collections, maxCollections);

        return normalizedScore * weightScore
                + recencyFactor * weightRecency
                + logViews * weightViews
                + logLikes * weightLikes
                + logComments * weightComments
                + logCollections * weightCollects
                + recentInteractionBoost(article.getId(), maxInteraction, interactionCounts);
    }

    /**
     * 近期互动热度加成：候选文章在最近 interactionWindowSeconds 内的跨用户阅读次数越高，加分越高。
     * 用候选集内最大次数归一化并封顶 interactionBoostMax，避免爆款对长期热度形成双重压制。
     */
    private double recentInteractionBoost(Long articleId, int maxInteraction,
                                          Map<Long, Integer> interactionCounts) {
        if (articleId == null || interactionCounts == null || interactionCounts.isEmpty()
                || interactionBoostMax <= 0 || maxInteraction <= 0) {
            return 0;
        }
        Integer count = interactionCounts.get(articleId);
        if (count == null || count <= 0) {
            return 0;
        }
        return Math.min((double) count / maxInteraction, 1.0) * interactionBoostMax;
    }

    /**
     * 服务端主动已读去重：查询当前用户近 excludeReadWindowDays 天内的浏览历史文章ID。
     * <p>与前端上报的 excludeIds 合并，从源头避免给已读用户再次推荐。</p>
     * @param userId 登录用户ID
     * @return 最近的已读文章ID（去重），异常时优雅降级为空集合
     */
    private List<Long> resolveServerReadIds(Integer userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        try {
            Date since = new Date(System.currentTimeMillis() - excludeReadWindowDays * 86400000L);
            List<ApBrowseHistory> history = apBrowseHistoryMapper.selectList(
                    new LambdaQueryWrapper<ApBrowseHistory>()
                            .eq(ApBrowseHistory::getUserId, userId.longValue())
                            .eq(ApBrowseHistory::getIsDeleted, false)
                            .gt(ApBrowseHistory::getBrowseTime, since)
                            .orderByDesc(ApBrowseHistory::getBrowseTime)
                            .last("LIMIT " + Math.max(1, excludeReadCount)));
            if (history == null) {
                return Collections.emptyList();
            }
            return history.stream()
                    .map(ApBrowseHistory::getArticleId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("查询服务端已读记录失败，降级为不主动去重 userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 加载候选文章在最近 interactionWindowSeconds 内的跨用户阅读次数（数据回流信号）。
     * <p>阅读行为已由业务链路写入 ap_browse_history，本方法仅做聚合，无需额外埋点。</p>
     */
    private Map<Long, Integer> loadRecentInteractionCounts(List<ApArticle> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> articleIds = new ArrayList<>();
        for (ApArticle a : candidates) {
            if (a.getId() != null) {
                articleIds.add(a.getId());
            }
        }
        if (articleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Date since = new Date(System.currentTimeMillis() - interactionWindowSeconds * 1000L);
            List<ArticleInteractionCountDTO> rows =
                    apBrowseHistoryMapper.selectRecentInteractionCounts(articleIds, since);
            Map<Long, Integer> map = new HashMap<>();
            if (rows != null) {
                for (ArticleInteractionCountDTO r : rows) {
                    if (r.getArticleId() != null && r.getCnt() != null) {
                        map.put(r.getArticleId(), r.getCnt().intValue());
                    }
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("读取近期互动热度失败，跳过该信号", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 记录一页推荐结果的曝光明细（数据回流闭环的输入）。
     * <p>最佳努力：失败仅记调试日志，绝不影响推荐主流程。匿名用户统一记为 user_id=0。</p>
     * @param user       当前用户（可能为 null）
     * @param channel    推荐渠道
     * @param page       页码
     * @param pageResult 本页返回的文章
     * @param seed       会话种子
     */
    private void recordExposure(ApUser user, String channel, int page,
                                List<ApArticle> pageResult, long seed) {
        if (pageResult == null || pageResult.isEmpty()) {
            return;
        }
        try {
            List<ApArticleExposure> rows = new ArrayList<>(pageResult.size());
            Date now = new Date();
            Long userId = (user != null && user.getId() != null) ? user.getId().longValue() : 0L;
            int pos = 0;
            for (ApArticle a : pageResult) {
                if (a.getId() != null) {
                    ApArticleExposure e = new ApArticleExposure();
                    e.setUserId(userId);
                    e.setArticleId(a.getId());
                    e.setChannel(channel);
                    e.setSubTab(SUB_TAB_RECOMMEND);
                    e.setPage(page);
                    e.setPosition(pos);
                    e.setSeed(seed);
                    e.setCreateTime(now);
                    rows.add(e);
                }
                pos++;
            }
            if (!rows.isEmpty()) {
                apArticleExposureMapper.insertBatch(rows);
            }
        } catch (Exception e) {
            // 曝光只是回流信号，失败不该拖垮推荐主链路
            log.debug("记录推荐曝光失败，忽略（不影响推荐主流程）", e);
        }
    }

    /**
     * 加载指定用户近期（exposureWindowDays 天）对候选文章的曝光次数。
     * <p>负反馈闭环：曝光次数越高而文章未被阅读/点击，说明用户对其兴趣越低，据此降权。</p>
     * @param candidates 候选文章
     * @param userId     登录用户ID
     * @return 文章ID → 曝光次数；异常时优雅降级为空 Map
     */
    private Map<Long, Integer> loadUserExposureCounts(List<ApArticle> candidates, Integer userId) {
        if (candidates == null || candidates.isEmpty() || userId == null) {
            return Collections.emptyMap();
        }
        List<Long> articleIds = new ArrayList<>();
        for (ApArticle a : candidates) {
            if (a.getId() != null) {
                articleIds.add(a.getId());
            }
        }
        if (articleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Date since = new Date(System.currentTimeMillis() - exposureWindowDays * 86400000L);
            List<ArticleInteractionCountDTO> rows =
                    apArticleExposureMapper.selectUserExposureCounts(userId.longValue(), articleIds, since);
            Map<Long, Integer> map = new HashMap<>();
            if (rows != null) {
                for (ArticleInteractionCountDTO r : rows) {
                    if (r.getArticleId() != null && r.getCnt() != null) {
                        map.put(r.getArticleId(), r.getCnt().intValue());
                    }
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("读取用户近期曝光次数失败，跳过负反馈 userId={}", userId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 曝光而未消费的降权：按曝光次数在候选集内归一化并封顶 exposurePenaltyMax。
     * 仅针对仍出现在候选里的文章（已读的被 exclude 掉了），因此出现即是「曝光而未消费」。
     */
    private double exposurePenalty(Map<Long, Integer> exposureCounts, int maxExposure, Long articleId) {
        if (articleId == null || exposureCounts == null || exposureCounts.isEmpty()
                || exposurePenaltyMax <= 0 || maxExposure <= 0) {
            return 0;
        }
        Integer count = exposureCounts.get(articleId);
        if (count == null || count <= 0) {
            return 0;
        }
        return Math.min((double) count / maxExposure, 1.0) * exposurePenaltyMax;
    }

    /**
     * 对数归一化：log(1+x)/log(1+max)，max<=0 时返回 0。
     */
    private double logNorm(int value, int max) {
        if (max <= 0) return 0;
        return Math.log(1 + value) / Math.log(1 + max);
    }

    /**
     * 获取用户兴趣画像（带 Redis 缓存）。
     * <p>
     * 命中缓存直接返回 JSON 反序列化结果；未命中则调用 {@link #buildInterestWeights} 回源计算并写入缓存。
     * Redisson 未装配或操作异常时优雅降级为直接计算，不影响推荐主流程。
     * 缓存 TTL 见 {@code recommend.interest-cache-ttl}（默认 30 分钟）。
     * </p>
     * @param userId 登录用户ID
     * @return 标签名 → 兴趣权重；无数据或缓存异常时可能为空 Map
     */
    private Map<String, Double> getInterestWeights(Integer userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        String cacheKey = INTEREST_CACHE_KEY + userId;

        // 1. 读缓存（缺失或反序列化失败 → 回源）
        if (redissonClient != null) {
            try {
                RBucket<String> bucket = redissonClient.getBucket(cacheKey);
                String cachedJson = bucket.get();
                if (cachedJson != null && !cachedJson.isEmpty()) {
                    Map<String, Double> cached = objectMapper.readValue(cachedJson,
                            new TypeReference<Map<String, Double>>() {});
                    if (cached != null) {
                        return cached;
                    }
                }
            } catch (Exception e) {
                log.warn("读取兴趣画像缓存失败，回源计算 userId={}, key={}", userId, cacheKey, e);
            }
        }

        // 2. 回源计算并写缓存（空画像也缓存，避免冷启动用户反复命中 DB）
        Map<String, Double> weights = buildInterestWeights(userId);
        if (redissonClient != null) {
            try {
                String json = objectMapper.writeValueAsString(weights);
                redissonClient.getBucket(cacheKey).set(json, interestCacheTtlSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("写入兴趣画像缓存失败，忽略 userId={}, key={}", userId, cacheKey, e);
            }
        }
        return weights;
    }

    /**
     * 基于登录用户的行为历史提炼兴趣标签权重。
     * <p>
     * 行为权重：收藏 &gt; 点赞 &gt; 浏览。先按行为折算文章的权重，
     * 再通过这些文章的标签聚合出「标签 → 累计权重」映射。
     * 匿名/无行为用户返回空 Map，调用方据此跳过个性化加成。
     * </p>
     * @param userId 登录用户ID
     * @return 标签名 → 累计兴趣权重；无数据时为空 Map
     */
    private Map<String, Double> buildInterestWeights(Integer userId) {
        Map<Long, Double> articleWeights = new HashMap<>();
        try {
            // 1. 最近浏览（仅取最近浏览的采样，控制 DB 与内存成本）
            List<ApBrowseHistory> browseList = apBrowseHistoryMapper.selectList(
                    new LambdaQueryWrapper<ApBrowseHistory>()
                            .eq(ApBrowseHistory::getUserId, userId.longValue())
                            .eq(ApBrowseHistory::getIsDeleted, false)
                            .orderByDesc(ApBrowseHistory::getBrowseTime)
                            .last("LIMIT " + interestBrowseSample));
            if (browseList != null) {
                for (ApBrowseHistory h : browseList) {
                    if (h.getArticleId() != null) {
                        articleWeights.merge(h.getArticleId(), WEIGHT_BROWSE, Double::sum);
                    }
                }
            }
            // 2. 点赞（operation=0 表示点赞态；仅统计文章的交互，在聚合阶段靠标签过滤噪声）
            List<ApBehaviorLikes> likeList = apBehaviorLikesMapper.selectList(
                    new LambdaQueryWrapper<ApBehaviorLikes>()
                            .eq(ApBehaviorLikes::getUserId, userId)
                            .eq(ApBehaviorLikes::getOperation, 0)
                            .orderByDesc(ApBehaviorLikes::getCreatedTime)
                            .last("LIMIT 200"));
            if (likeList != null) {
                for (ApBehaviorLikes l : likeList) {
                    if (l.getEntryId() != null) {
                        articleWeights.merge(l.getEntryId(), WEIGHT_LIKE, Double::sum);
                    }
                }
            }
            // 3. 收藏
            List<ApCollection> collectList = apCollectionMapper.selectList(
                    new LambdaQueryWrapper<ApCollection>()
                            .eq(ApCollection::getUserId, userId)
                            .orderByDesc(ApCollection::getCreatedTime)
                            .last("LIMIT 200"));
            if (collectList != null) {
                for (ApCollection c : collectList) {
                    if (c.getArticleId() != null) {
                        articleWeights.merge(c.getArticleId(), WEIGHT_COLLECT, Double::sum);
                    }
                }
            }
        } catch (Exception e) {
            // 兴趣画像属于非关键增强，失败时优雅降级为无个性化
            log.warn("构建用户兴趣画像失败，跳过个性化 userId={}", userId, e);
            return Collections.emptyMap();
        }

        if (articleWeights.isEmpty()) {
            return Collections.emptyMap();
        }

        // 4. 回读这些文章的标签，聚合标签权重
        List<ApArticle> interactedArticles = apArticleMapper.selectBatchIds(articleWeights.keySet());
        Map<String, Double> interestWeights = new HashMap<>();
        if (interactedArticles != null) {
            for (ApArticle art : interactedArticles) {
                Double artWeight = articleWeights.get(art.getId());
                if (artWeight == null || art.getTags() == null || art.getTags().isEmpty()) {
                    continue;
                }
                for (String tag : art.getTags()) {
                    if (tag != null && !tag.trim().isEmpty()
                            && !tag.trim().equals(untaggedBucket)
                            && !tag.trim().equals(UNTAGGED)) {
                        interestWeights.merge(tag.trim(), artWeight, Double::sum);
                    }
                }
            }
        }
        return interestWeights;
    }

    /**
     * 兴趣加成：文章命中用户兴趣标签时，按其最高匹配度给出 0~interestBoostMax 的加分。
     * 用兴趣权重最大值归一化，避免单一强势标签垄断推荐。
     */
    private double interestBoost(Map<String, Double> interestWeights, double interestWeightMax,
                                 ApArticle article) {
        if (interestWeights.isEmpty() || interestWeightMax <= 0
                || article.getTags() == null || article.getTags().isEmpty()) {
            return 0;
        }
        double best = 0;
        for (String tag : article.getTags()) {
            Double w = interestWeights.get(tag);
            if (w != null) {
                best = Math.max(best, w);
            }
        }
        if (best <= 0) {
            return 0;
        }
        return Math.min(best / interestWeightMax, 1.0) * interestBoostMax;
    }

    /**
     * 基于 seed + articleId 的确定性伪随机抖动（取值范围 [-seedJitter, +seedJitter]）。
     * <p>
     * 同一 seed 下抖动恒定 → 同 seed 分页排序连续；不同 seed（刷新）抖动不同 → 排序打散出新内容。
     * 幅度受 seedJitter 约束，不会扭曲高分文章的领先优势。
     * </p>
     */
    private double seededJitter(long seed, Long articleId) {
        if (articleId == null) {
            return 0;
        }
        long h = seed * 31L + articleId;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        double r = (h & Long.MAX_VALUE) / (double) Long.MAX_VALUE; // [0,1]
        return (r - 0.5) * 2.0 * seedJitter; // [-seedJitter, +seedJitter]
    }

    private Map<String, Object> buildEmptyResponse(long seed, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        result.put("list", Collections.emptyList());
        result.put("seed", seed);
        result.put("page", page);
        result.put("size", size);
        result.put("hasMore", false);
        result.put("total", 0);
        return result;
    }
}