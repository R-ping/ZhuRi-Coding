package com.zhuri.coding.model.coding.vos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 能力档案（Coding 延展第二层）
 *
 * <p>五块：技术领域分布 / 持续度 / 输出能力 / 解决问题（依赖付费问答，暂不可用）/ 测评成绩。
 * 每块含两个语义字段：</p>
 * <ul>
 *   <li>{@code available}：是否有数据可展示（false → 前端显示"暂无数据"）；</li>
 *   <li>{@code public}：分项是否公开（false → 前端显示"未公开"占位；本人视角为开关回显）。</li>
 * </ul>
 *
 * <p>视角约定：{@code viewer=self} 全量返回；{@code viewer=visitor} 时整体未公开只返回
 * {@code isPublic=false}（各块保持默认空态），已公开则按分项开关逐块裁剪。</p>
 */
@Data
public class CodingAbilityProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 档案所有者用户ID */
    private Integer userId;

    /** 昵称（用户服务不可用时留空） */
    private String nickname;

    /** 头像（用户服务不可用时留空） */
    private String avatar;

    /** 档案整体是否公开（本人/访客都返回，供前端区分"未公开"占位） */
    private Boolean isPublic;

    /** 视角：self 本人 / visitor 访客（未登录也为 visitor） */
    private String viewer;

    /** 五块数据 */
    private Blocks blocks;

    @Data
    public static class Blocks implements Serializable {

        private static final long serialVersionUID = 1L;

        private DomainBlock domain = new DomainBlock();
        private StreakBlock streak = new StreakBlock();
        private OutputBlock output = new OutputBlock();
        private SolveBlock solve = new SolveBlock();
        private AssessmentBlock assessment = new AssessmentBlock();
    }

    /** 技术领域分布（答题领域统计，按答题量降序前 8） */
    @Data
    public static class DomainBlock implements Serializable {

        private static final long serialVersionUID = 1L;

        private Boolean available = false;

        @JsonProperty("public")
        private Boolean publicVisible = false;

        private List<DomainItem> items;
    }

    /** 单个领域的答题分布 */
    @Data
    public static class DomainItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 领域（题目标签） */
        private String tag;

        /** 答题总数 */
        private Integer total;

        /** 答对总数 */
        private Integer correct;
    }

    /** 持续度（签到连续天数 + 活跃月份数） */
    @Data
    public static class StreakBlock implements Serializable {

        private static final long serialVersionUID = 1L;

        private Boolean available = false;

        @JsonProperty("public")
        private Boolean publicVisible = false;

        /** 连续答题天数（签到体系唯一来源，不可用时降级 0） */
        private Integer continuousDays;

        /** 活跃月份数（有作答记录的自然月数） */
        private Integer activeMonths;

        /** 首次答题日期（yyyy-MM-dd） */
        private String firstAnswerDate;

        /** 最近答题日期（yyyy-MM-dd） */
        private String lastAnswerDate;
    }

    /** 输出能力（已发布文章数 + 被收藏数） */
    @Data
    public static class OutputBlock implements Serializable {

        private static final long serialVersionUID = 1L;

        private Boolean available = false;

        @JsonProperty("public")
        private Boolean publicVisible = false;

        /** 已发布文章数 */
        private Integer articleCount;

        /** 文章被收藏数（他人收藏我的文章） */
        private Integer collectedCount;
    }

    /** 解决问题（依赖付费问答，未上线前恒 available=false） */
    @Data
    public static class SolveBlock implements Serializable {

        private static final long serialVersionUID = 1L;

        private Boolean available = false;
    }

    /** 测评成绩（最近一次已提交） */
    @Data
    public static class AssessmentBlock implements Serializable {

        private static final long serialVersionUID = 1L;

        private Boolean available = false;

        @JsonProperty("public")
        private Boolean publicVisible = false;

        /** 得分（0-100） */
        private Integer score;

        /** 答对题数 */
        private Integer correctCount;

        /** 总题数 */
        private Integer totalCount;

        /** 百分位（样本 < 20 时为 0，前端仅展示 >0 的值） */
        private Integer percentile;

        /** 交卷时间（yyyy-MM-dd HH:mm:ss） */
        private String submittedTime;
    }
}