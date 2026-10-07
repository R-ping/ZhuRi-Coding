package com.zhuri.coding.model.admin.vos;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 运营侧「内容折叠」列表项。
 *
 * <p>刻意把 {@code content}（评论正文）带出来：运营判"要不要折叠/要不要恢复"看的只能是正文本身，
 * 只给一个 id 等于让人先跳去 C 端详情页看，再回来操作 —— 一来一回的操作成本会让人放弃复核，
 * 折叠就变成单向动作（只能折叠、没人恢复）。
 */
@Data
public class AdminCommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评论ID */
    private Long id;

    /** 类型：COMMENT 文章评论 / PINS_COMMENT 沸点评论 */
    private String targetType;

    /** 所属主体：文章评论为 articleId，沸点评论为 pinsId —— 用于前端跳转到原文 */
    private Long ownerId;

    /** 评论者账号ID */
    private Integer authorId;

    private String authorName;

    /** 评论正文 */
    private String content;

    /** 0 正常 / 1 已折叠 */
    private Integer hidden;

    private Date createdTime;
}
