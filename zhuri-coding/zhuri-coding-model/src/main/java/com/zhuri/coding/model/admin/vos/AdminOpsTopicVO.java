package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;

/**
 * 运营后台 · 话题条目（推荐位清单与候选列表共用）。
 *
 * <p>与 {@link AdminOpsCircleVO} 同样的取舍：候选项与已配置项共用一个 VO，
 * {@link #recommendOrder} 为 {@code null} 表示当前不在推荐位。
 *
 * <p><b>{@link #status} 要带出来，不能只在候选里过滤掉</b>：C 端推荐话题的查询条件是
 * {@code is_recommend = 1 AND status = 1}，也就是说一个被停用的话题即使还挂在推荐位上，
 * 用户侧也是看不见的 —— 这种"配置里在、页面上不在"的不一致，只有在运营后台能看到
 * {@code status} 才解释得通。候选列表因此也不按 status 过滤。
 */
@Data
public class AdminOpsTopicVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 话题ID */
    private Long topicId;

    /** 话题名称 */
    private String name;

    /** 角标文字 */
    private String badge;

    /** 话题状态：1 启用 / 0 停用（停用的话题不会出现在 C 端） */
    private Integer status;

    /** 参与人数 */
    private Long participantCount;

    /** 总阅读数 */
    private Long viewCount;

    /**
     * 推荐位次（从 1 开始）。
     *
     * <p>{@code null} = 该话题当前不在推荐位。
     */
    private Integer recommendOrder;
}
