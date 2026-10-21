package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 每日一题作答记录（Coding 延展第一层 · 简答）
 *
 * <p>一天一条，靠 {@code uk_user_date} 保证并发下也只落一条。
 * 「自由练习」这个概念已经消失，所以不再需要区分当日题与练习，
 * 也不再用「生成列 + 条件唯一键」那套手法。</p>
 *
 * <p><b>这条流水有两个阶段</b>：抽题时先落一条 {@code status = 未作答}（{@code user_answer} 为空串），
 * 用户提交后再改成 {@code 已作答}。所以「今天发给你的是哪道题」是数据库里的一件事，
 * 不需要额外的地方去记 —— 这也是抽题阶段不依赖 Redis 的原因。</p>
 *
 * <p>{@code level} 为综合等级（1-5），由服务端按结构 / 覆盖度 / 准确性三维均值算出；
 * 评估降级时它可以为 NULL（表示"这次没评出来"），读取方不要把 NULL 当 0 分。</p>
 */
@Data
@TableName("ap_coding_answer_record")
public class ApCodingAnswerRecord implements Serializable {

    /** 状态：已发放未作答（抽题时落这条） */
    public static final int STATUS_ISSUED = 0;
    /** 状态：已作答（提交后改成这条） */
    public static final int STATUS_ANSWERED = 1;

    /** 该流水是否已经作答（false = 只发放过、还没提交） */
    public static boolean isAnswered(ApCodingAnswerRecord record) {
        return record != null && record.getStatus() != null
            && record.getStatus() == STATUS_ANSWERED;
    }

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 作答用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 题目池ID */
    @TableField("pool_id")
    private Long poolId;

    /** 作答日期（按天限次） */
    @TableField("answer_date")
    private Date answerDate;

    /** 用户作答文本 */
    @TableField("user_answer")
    private String userAnswer;

    /** 综合等级 1-5；未评估为 NULL */
    @TableField("level")
    private Integer level;

    /** 点评 */
    @TableField("feedback")
    private String feedback;

    /** 已覆盖考点（JSON 数组） */
    @TableField("covered")
    private String covered;

    /** 未覆盖考点（JSON 数组） */
    @TableField("missing")
    private String missing;

    /** 作答用时（秒） */
    @TableField("elapsed_seconds")
    private Integer elapsedSeconds;

    /** 本次获得的逐日分 */
    @TableField("score_awarded")
    private Integer scoreAwarded;

    /** 状态：0=已发放未作答 1=已作答 */
    @TableField("status")
    private Integer status;

    @TableField("created_time")
    private Date createdTime;
}
