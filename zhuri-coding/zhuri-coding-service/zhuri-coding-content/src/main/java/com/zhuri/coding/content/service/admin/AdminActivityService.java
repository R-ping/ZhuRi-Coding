package com.zhuri.coding.content.service.admin;

import com.zhuri.coding.model.admin.dtos.AdminActivitySaveDto;
import com.zhuri.coding.model.common.dtos.ResponseResult;

/**
 * 运营后台 · 活动管理（CMS）。
 *
 * <p><b>为什么这是一整条链路而不是一个开关</b>：{@code ap_activity} 原先只有两个只读接口
 * （{@code GET /api/v1/activities/list} 与 {@code GET /{id}}），全仓库没有任何写入点 ——
 * 表里的活动只能靠手写 SQL 造出来。所以缺的不是"启用/停用"这种单点能力，
 * 而是「建档 → 编辑 → 对外可见 → 撤下」这一整条运营动作链。少任何一环，
 * 剩下的部分都不可用：能建不能改就得删了重建，能改不能下线就只能等它自然结束。
 *
 * <p><b>三个动作，三种审计</b>：新建、编辑、上线、下线、删除草稿各自是一次独立的运营动作，
 * 各有各的理由（"为什么要上线"和"为什么这么改标题"不是同一件事），所以拆成独立端点、
 * 独立审计记录，而不是一个"保存"接口用参数区分。理由见 {@code AdminOpsConfigController}
 * 里关于 PUT / POST 的说明：这里是"再做一次动作"的语义，全部走 POST。
 *
 * <p><b>新建一律先落草稿</b>（{@link com.zhuri.coding.model.activity.ActivityStatus#DRAFT}），
 * 即便调用方想一步到位上线。理由是审计：把"建档"和"对外发布"合成一次调用，
 * 事后只看得到一条记录，无法回答"它是什么时候对外可见的"。上线是独立动作，
 * 有独立的理由与独立的时间点。代价是前端建完要再点一次上线 —— 这个代价很小，
 * 而它换来的是发布行为可追溯。
 *
 * <p><b>幂等闸口分两种</b>：
 * <ul>
 *   <li>带状态的三个动作（上线 / 下线 / 删除）按<b>状态比对</b> —— 已经是目标状态时明确报错
 *       （"活动已是进行中，无需上线"），而不是静默返回成功；</li>
 *   <li>编辑按<b>字段比对</b> —— 一个字段都没变时报"信息与当前一致，无需保存"。
 *       理由与 {@code AdminOpsConfigService} 一致：静默成功会让运营以为自己刚改了什么。</li>
 * </ul>
 *
 * <p><b>状态推进的责任划分</b>：上线时由 {@link com.zhuri.coding.model.activity.ActivityStatus#phaseOf}
 * 按日期<b>一次算准</b>该落的阶段（重新上线一条早已过期的活动，直接就是"已结束"）；
 * 之后"即将开始 → 进行中 → 已结束"由定时任务 {@code ActivityLifecycleTask} 按日历推进。
 * 也就是说：运营只决定"可不可见"，时间决定"处在哪一段"。
 */
public interface AdminActivityService {

    /** 审计动作：新建活动 */
    String ACTION_CREATE = "ACTIVITY_CREATE";

    /** 审计动作：编辑活动 */
    String ACTION_UPDATE = "ACTIVITY_UPDATE";

    /** 审计动作：上线活动 */
    String ACTION_PUBLISH = "ACTIVITY_PUBLISH";

    /** 审计动作：下线活动 */
    String ACTION_OFFLINE = "ACTIVITY_OFFLINE";

    /** 审计动作：删除草稿 */
    String ACTION_DELETE = "ACTIVITY_DELETE";

    /** 审计对象类型：活动 */
    String TARGET_ACTIVITY = "ACTIVITY";

    /** 列表单页上限（人工维护的活动不需要一次拉几百条，同时拦住误传的 size=100000） */
    int MAX_PAGE_SIZE = 50;

    /** 列表默认每页条数 */
    int DEFAULT_PAGE_SIZE = 20;

    /** 标题长度上限，与 {@code ap_activity.title varchar(200)} 等长 */
    int TITLE_MAX_LEN = 200;

    /** 封面 URL 长度上限，与 {@code ap_activity.cover_image varchar(255)} 等长 */
    int COVER_MAX_LEN = 255;

    /**
     * 描述长度上限。
     *
     * <p>列是 {@code text}（理论 64KB），所以这个数字不是列宽约束，而是产品判断：
     * 活动描述是列表页与详情页上的一段导语，超过这个量级就不是"描述"而是正文了，
     * 而正文应该发成文章。设一条线同时也拦住了把整篇 HTML 粘进来的用法。
     */
    int DESC_MAX_LEN = 5000;

    /**
     * 活动列表（含草稿与已下线）。
     *
     * <p>默认不过滤状态 —— 运营需要看到全部，包括还没发布的草稿。
     * 这与 C 端读路径刚好相反（那边必须永远排除不可见的两种），
     * 差异体现的是"谁在看":运营看的是**库里的全部**，用户看的是**对外可见的部分**。
     *
     * @param keyword  标题关键字；为空表示不过滤
     * @param status   状态编码；为空表示全部。取值不在白名单内直接报参数错误，
     *                 而不是返回空列表 —— 拼错状态名却得到一页空白，是排查成本最高的一种反馈
     * @param type     活动类型；为空表示全部
     * @param category 活动分类；为空表示全部
     */
    ResponseResult page(String keyword, String status, String type, String category,
                        Integer page, Integer size);

    /** 活动详情（含草稿/已下线） */
    ResponseResult detail(Long id);

    /**
     * 新建活动（落草稿）。
     *
     * @return 成功时返回新活动的 id 与详情
     */
    ResponseResult create(AdminActivitySaveDto dto);

    /**
     * 编辑活动。
     *
     * <p>已发布的活动改日期会**重算阶段**：把结束日期往后延，一条"已结束"的活动
     * 会重新变成"进行中"。这是延期的正常用法，不是异常。草稿与已下线改日期不重算
     * —— 它们本来就与时间无关，上线时才算。
     *
     * @return 成功时返回更新后的详情
     */
    ResponseResult update(Long id, AdminActivitySaveDto dto);

    /**
     * 上线（草稿 / 已下线 → 按日期定阶段）。
     *
     * @param id     活动ID
     * @param reason 操作理由（必填）
     */
    ResponseResult publish(Long id, String reason);

    /**
     * 下线（已发布 → 已下线）。
     *
     * <p>只改可见性，不删数据：参与人数、阅读量这些统计要留着（历史活动仍会在
     * 数据看板里被引用）。真正需要抹掉的只有"建错了的草稿"，那是 {@link #delete}。
     *
     * @param id     活动ID
     * @param reason 操作理由（必填）
     */
    ResponseResult offline(Long id, String reason);

    /**
     * 删除活动（<b>仅限草稿</b>）。
     *
     * <p>物理删除，所以只对草稿开放 —— 草稿从未对外可见，删掉它不会改变任何用户看到的东西，
     * 也不会有哪条统计数据引用它。已发布的活动（哪怕已结束）一律拒绝，请改用下线：
     * "下架"与"从历史里抹去"是两件事，后者不该由 CMS 一个按钮顺手做掉。
     *
     * @param id     活动ID
     * @param reason 操作理由（必填）
     */
    ResponseResult delete(Long id, String reason);
}
