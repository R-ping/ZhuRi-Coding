package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 运营侧举报队列条目。
 *
 * <p>与 C 端"我提交的举报"不同，这里要能支撑**判断**：
 * 被举报的是什么内容、谁写的、举报人说了什么、是否已处置过。
 * 因此比 C 端多出内容标题与处置结论。
 */
@Data
public class ArticleReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 举报记录ID（处置时回传） */
    private Long id;

    /** 被举报文章ID */
    private Long articleId;

    /** 被举报文章标题（批量回填，便于队列里直接判断） */
    private String articleTitle;

    /** 被举报文章当前状态（0草稿 1审核中 2未通过 3已下架 9已发布）；文章已删除时为 null */
    private Byte articleStatus;

    /** 该文章当前的**待处理**举报条数（同一篇被多人举报是重要的排期信号） */
    private Integer pendingCountOfArticle;

    /** 被举报文章作者ID */
    private Long authorId;

    /** 举报人ID */
    private Integer userId;

    /** 举报原因（选项值） */
    private String reason;

    /** 举报补充说明 */
    private String description;

    /** 举报截图URL列表 */
    private List<String> imageUrls;

    /** 处理状态：0待处理 1已处理 */
    private Integer status;

    /** 处置结论：1驳回举报 2警告作者 3下架内容；未处置为 null */
    private Integer handleResult;

    /** 处置说明（回执给举报人的内容） */
    private String handleReason;

    /** 处置人账号ID */
    private Integer handlerId;

    /** 处置时间 */
    private Date handleTime;

    /** 回执通知状态：0未发 1已发 2发送失败 */
    private Integer notifyStatus;

    /** 举报提交时间 */
    private Date createdTime;
}
