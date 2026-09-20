package com.heima.model.comment.dtos;

import lombok.Data;

/**
 * 创作者中心评论管理 - 文章评论状态列表查询 DTO
 */
@Data
public class CommentManageDto {
    /** 当前页码（从1开始） */
    private Integer page;
    /** 每页条数 */
    private Integer size;
    /** 评论开关状态筛选：1-开放 0-关闭 null-全部 */
    private Integer status;
    /** 文章标题关键字 */
    private String keyword;
}