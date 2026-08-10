package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Data
@TableName("ap_article_draft")
public class ApArticleDraft implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("article_id")
    private Long articleId;

    private String title;

    @TableField("author_id")
    private Long authorId;

    @TableField("channel_id")
    private Integer channelId;

    @TableField("channel_name")
    private String channelName;

    private Short layout;

    private String coverImage;

    @TableField("column_id")
    private Long columnId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private String topic;

    private String content;

    private String summary;

    @TableField("publish_time")
    private Date publishTime;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    private Byte status;
    /**
     * 是否删除 0 未删除 1 已删除
     */
    @TableField("is_deleted")
    private Boolean isDeleted = false;
    /**
     * 内容里嵌入的图片列表
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ContPic> contPics;
    @Data
    public static class ContPic {
        private String picUri;
        private String picUrl;
    }
}