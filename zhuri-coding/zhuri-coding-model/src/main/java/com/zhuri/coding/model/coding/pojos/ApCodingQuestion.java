package com.zhuri.coding.model.coding.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 每日一题题库（Coding 延展第一层）
 *
 * <p>options / answer 以 JSON 字符串存储（选项文本数组 / 正确选项下标数组），
 * 服务层用 Jackson 解析，避免依赖数据库 JSON 列类型。</p>
 */
@Data
@TableName("ap_coding_question")
public class ApCodingQuestion implements Serializable {

    /** 题型：单选 */
    public static final int TYPE_SINGLE = 1;
    /** 题型：多选 */
    public static final int TYPE_MULTIPLE = 2;

    /** 难度：入门 */
    public static final int DIFFICULTY_EASY = 1;
    /** 难度：进阶 */
    public static final int DIFFICULTY_MEDIUM = 2;
    /** 难度：挑战 */
    public static final int DIFFICULTY_HARD = 3;

    /** 状态：待审核 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已上架 */
    public static final int STATUS_PUBLISHED = 1;
    /** 状态：已驳回 */
    public static final int STATUS_REJECTED = 2;

    /** 来源：平台自建 */
    public static final int SOURCE_PLATFORM = 0;
    /**
     * 来源：文章 AI 生成。
     *
     * <p><b>已下线</b>：不再产生新数据。常量保留是为了让历史行（source_type = 1）还能被解释。</p>
     */
    public static final int SOURCE_AI = 1;
    /** 来源：作者投稿 */
    public static final int SOURCE_AUTHOR = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 题干 */
    @TableField("stem")
    private String stem;

    /** 题型：1单选 2多选 */
    @TableField("question_type")
    private Integer questionType;

    /** 选项（JSON数组，元素为选项文本） */
    @TableField("options")
    private String options;

    /** 正确选项下标（JSON数组，如 [0] 或 [0,2]） */
    @TableField("answer")
    private String answer;

    /** 答案解析 */
    @TableField("explanation")
    private String explanation;

    /** 难度：1入门 2进阶 3挑战 */
    @TableField("difficulty")
    private Integer difficulty;

    /** 知识点标签（逗号分隔） */
    @TableField("tags")
    private String tags;

    /** 题干MD5（全局去重） */
    @TableField("stem_hash")
    private String stemHash;

    /** 来源：0平台 1文章AI生成 2作者投稿 */
    @TableField("source_type")
    private Integer sourceType;

    /**
     * 来源文章ID。
     *
     * <p><b>已停用</b>：不再写入也不再生效。列和字段都留着 —— 历史数据（AI 生成的题）靠它可回溯，
     * 题目表只有百级数据，留着比删列安全。</p>
     */
    @TableField("source_article_id")
    private Long sourceArticleId;

    /** 出题人用户ID（投稿） */
    @TableField("source_user_id")
    private Long sourceUserId;

    /** 状态：0待审核 1已上架 2已驳回 */
    @TableField("status")
    private Integer status;

    /** 驳回原因 */
    @TableField("reject_reason")
    private String rejectReason;

    /** 累计作答次数 */
    @TableField("answer_count")
    private Integer answerCount;

    /** 累计答对次数 */
    @TableField("correct_count")
    private Integer correctCount;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}