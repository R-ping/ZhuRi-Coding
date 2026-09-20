package com.zhuri.coding.model.pins.vos;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 沸点列表项 VO
 */
@Data
@NoArgsConstructor
public class PinsVO {

    private Long id;
    private Long userId;
    private String userName = "";
    private String userAvatar = "";
    private Long authorId;
    private String authorName = "";
    private String authorImage = "";
    private String content = "";
    private List<String> imageUrls = new ArrayList<>();
    private List<String> topicTags = new ArrayList<>();
    /** 所属话题ID（用于跳转话题详情页） */
    private Long topicId;
    /** 所属圈子ID（用于跳转圈子详情页） */
    private Long circleId;
    /** 所属圈子名称 */
    private String circleName = "";
    private String linkUrl = "";
    private String linkTitle = "";
    private Integer likeCount = 0;
    private Integer commentCount = 0;
    private Integer shareCount = 0;
    private Boolean liked = false;
    private Date createdTime;
    private Date publishTime;
    private Date reviewTime;
}