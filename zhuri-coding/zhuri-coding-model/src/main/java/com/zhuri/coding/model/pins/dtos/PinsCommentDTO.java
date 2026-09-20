package com.zhuri.coding.model.pins.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论请求 DTO
 */
@Data
@NoArgsConstructor
public class PinsCommentDTO {

    private Long pinsId;

    private String content = "";

    private Long parentId;

    /** 评论图片URL列表 */
    private List<String> imageUrls = new ArrayList<>();

    /** 被回复用户ID（回复二级评论时使用） */
    private Integer replyToUserId;

    /** 被回复用户昵称（回复二级评论时使用） */
    private String replyToUserName = "";
}