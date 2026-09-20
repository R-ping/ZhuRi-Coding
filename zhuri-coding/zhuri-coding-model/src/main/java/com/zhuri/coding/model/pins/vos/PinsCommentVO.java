package com.zhuri.coding.model.pins.vos;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 沸点评论 VO
 */
@Data
@NoArgsConstructor
public class PinsCommentVO {

    private Long id;
    private Long pinsId;
    private Integer userId;
    private String userName = "";
    private String userAvatar = "";
    private Long parentId;
    private String content = "";
    private List<String> imageUrls = new ArrayList<>();
    private Integer replyToUserId;
    private String replyToUserName = "";
    private Integer likeCount = 0;
    private Integer replyCount = 0;
    private List<PinsCommentVO> replies = new ArrayList<>();
    private Date createdTime;
}