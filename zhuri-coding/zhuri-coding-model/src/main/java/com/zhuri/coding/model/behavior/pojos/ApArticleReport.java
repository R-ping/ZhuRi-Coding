package com.zhuri.coding.model.behavior.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文章举报记录
 *
 * <p>生命周期：提交时 {@code status=0} 待处理 → 运营处置后置 {@code status=1} 并写入
 * {@code handleResult}（怎么处置的）与 {@code handleReason}（为什么，会回执给举报人）。
 * {@code status} 只表达"办没办"，{@code handleResult} 才表达"怎么办的"——两者不可合并，
 * 因为回执内容来自后者。
 */
@Data
@TableName("ap_article_report")
public class ApArticleReport implements Serializable {

    /** 处理状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 处理状态：已处理 */
    public static final int STATUS_HANDLED = 1;

    /** 处置结论：驳回举报（内容无问题） */
    public static final int RESULT_REJECT = 1;
    /** 处置结论：警告作者（通知作者但不改内容） */
    public static final int RESULT_WARN_AUTHOR = 2;
    /** 处置结论：下架内容 */
    public static final int RESULT_TAKE_DOWN = 3;

    /** 回执通知：未发送 */
    public static final int NOTIFY_PENDING = 0;
    /** 回执通知：已发送 */
    public static final int NOTIFY_SENT = 1;
    /** 回执通知：发送失败（可重试） */
    public static final int NOTIFY_FAILED = 2;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 举报人ID */
    @TableField("user_id")
    private Integer userId;

    /** 被举报文章ID */
    @TableField("article_id")
    private Long articleId;

    /** 被举报文章作者ID */
    @TableField("author_id")
    private Long authorId;

    /** 举报原因 */
    @TableField("reason")
    private String reason;

    /** 补充说明（≤100字） */
    @TableField("description")
    private String description;

    /** 举报图片URL（逗号分隔，最多4张） */
    @TableField("image_urls")
    private String imageUrls;

    /** 处理状态：0待处理 1已处理 */
    @TableField("status")
    private Integer status;

    /** 处置结论：1驳回举报 2警告作者 3下架内容；未处置为 null */
    @TableField("handle_result")
    private Integer handleResult;

    /** 处置说明（运营填写，会回执给举报人） */
    @TableField("handle_reason")
    private String handleReason;

    /** 处置人账号ID */
    @TableField("handler_id")
    private Integer handlerId;

    /** 处置时间 */
    @TableField("handle_time")
    private Date handleTime;

    /** 回执通知状态：0未发 1已发 2发送失败（可重试） */
    @TableField("notify_status")
    private Integer notifyStatus;

    /** 创建时间 */
    @TableField("created_time")
    private Date createdTime;
}
