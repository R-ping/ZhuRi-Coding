package com.heima.model.audit.dtos;

import lombok.Data;

/**
 * 内容治理申诉提交请求
 */
@Data
public class AppealSubmitDto {

    /** 申诉对象：1-评论折叠 2-文章AIGC误标 */
    private Integer appealType;

    /** 被申诉内容ID（评论ID / 文章ID） */
    private Long contentId;

    /** 申诉理由（≤500 字） */
    private String reason;
}
