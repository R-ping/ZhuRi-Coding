package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.circle.pojos.ApCircleHotConfig;
import com.zhuri.coding.model.common.dtos.ResponseResult;

import java.util.List;

/**
 * 运营后台 · 运营位配置（人气圈子 / 推荐话题）。
 *
 * <p><b>为什么先做这两处</b>：全文扫下来，运营位散在三处，形态各不相同 ——
 * <ul>
 *   <li><b>人气圈子</b>：有配置表 {@code ap_circle_hot_config}、有 C 端读路径
 *       （{@code CircleService#hot} 按 {@code display_order} 取前 5），
 *       <b>但没有任何写入点</b> —— 这张表只能靠手写 SQL 维护；</li>
 *   <li><b>推荐话题</b>：{@code ap_topic.is_recommend / recommend_sort} 上有 C 端读路径
 *       （侧栏"推荐话题(换一换)"），<b>同样全仓没有写入点</b> ——
 *       {@code is_recommend} 字段建了、索引建了、查询写了，就是没人能把它置成 1；</li>
 *   <li><b>活动</b>：{@code ap_activity} 原本只有 {@code list} / {@code getById} 两个只读接口，
 *       连"新建一条活动"都没有。它缺的不是一个开关，而是新建/编辑/上下线整条链路，
 *       单补一个"启用/停用"没有可用性 —— 线上的活动还得靠手写 SQL 造出来。
 *       <b>2026-10-06 已由 {@code AdminActivityService} 补齐（活动 CMS）</b>，
 *       所以本接口不做活动相关的任何事，两者刻意分开：
 *       运营位改的是"已有内容出现在哪里"，活动是凭空新建一条对外可见的内容。</li>
 * </ul>
 *
 * <p>前两处是同一类缺口：<b>读路径齐了、只差"谁来定这份清单"</b>，补一个配置入口就能闭环，
 * 所以本次只做这两处。
 *
 * <p><b>能力边界（有意为之）</b>：这里只做"配置类动作"——定清单、定位次、上下推荐位，
 * 不做内容创建（不新建圈子、不新建话题、不编辑话题文案）。运营位是"挑已有内容摆上去"，
 * 内容的产生属于另一条链路。
 *
 * <p><b>幂等闸口是"与现状比对"</b>：整份清单提交两次，第二次会被明确拒掉
 * （"配置与当前一致，无需保存"）而不是静默重写一遍。理由同
 * {@code AdminContentFoldService}：静默成功会让运营以为自己刚改了什么，
 * 而审计里又多一条看不出差别的记录。
 */
public interface AdminOpsConfigService {

    /** 审计动作：设置人气圈子 */
    String ACTION_SET_HOT_CIRCLES = "OPS_HOT_CIRCLE_SET";

    /** 审计动作：设置推荐话题 */
    String ACTION_SET_RECOMMEND_TOPICS = "OPS_RECOMMEND_TOPIC_SET";

    /** 审计对象类型：人气圈子配置 */
    String TARGET_HOT_CIRCLE = "HOT_CIRCLE";

    /** 审计对象类型：推荐话题配置 */
    String TARGET_RECOMMEND_TOPIC = "RECOMMEND_TOPIC";

    /**
     * 人气位上限。
     *
     * <p>数值本身不在这里 —— 它定义在 {@code ApCircleHotConfig#MAX_DISPLAY_ORDER}
     * （连带 C 端 {@code CircleService#hot} 的 {@code LIMIT}），
     * 因为"人气位只有 5 个"是这张表的形态，不是运营模块的策略。
     * 在这里再声明一个同义常量，就又多了一处需要同步的地方。
     */
    int MAX_HOT_CIRCLES = ApCircleHotConfig.MAX_DISPLAY_ORDER;

    /**
     * 推荐话题上限。
     *
     * <p>C 端 {@code TopicService#recommend} 不限条数（按 size 做环形缓冲"换一换"），
     * 所以这个上限不是 C 端硬约束，而是给人工维护划一条线：一份需要人手排序的清单，
     * 超过 20 条就没人认真排了，同时它也是一道防呆（拦住误传的几千个 id）。
     */
    int MAX_RECOMMEND_TOPICS = 20;

    /** 候选列表单页上限（同上：人工挑选场景不需要一次拉几百条） */
    int MAX_PAGE_SIZE = 50;

    /** 候选列表默认每页条数 */
    int DEFAULT_PAGE_SIZE = 20;

    /** 当前人气圈子清单（按位次） */
    ResponseResult hotCircles();

    /**
     * 圈子候选列表（供运营挑选）。
     *
     * @param keyword 名称关键字；可为空表示不过滤
     */
    ResponseResult searchCircles(String keyword, Integer page, Integer size);

    /**
     * 整份替换人气圈子清单。
     *
     * @param circleIds 有序圈子ID（数组顺序即展示顺序，服务端从 1 重新编号）；
     *                  空列表表示清空人气位
     * @param reason    操作理由（必填，写审计）
     */
    ResponseResult saveHotCircles(List<Long> circleIds, String reason);

    /** 当前推荐话题清单（按位次） */
    ResponseResult recommendTopics();

    /**
     * 话题候选列表（供运营挑选）。
     *
     * @param keyword 名称关键字；可为空表示不过滤
     */
    ResponseResult searchTopics(String keyword, Integer page, Integer size);

    /**
     * 整份替换推荐话题清单。
     *
     * @param topicIds 有序话题ID（数组顺序即位次）；空列表表示清空推荐位
     * @param reason   操作理由（必填，写审计）
     */
    ResponseResult saveRecommendTopics(List<Long> topicIds, String reason);
}
