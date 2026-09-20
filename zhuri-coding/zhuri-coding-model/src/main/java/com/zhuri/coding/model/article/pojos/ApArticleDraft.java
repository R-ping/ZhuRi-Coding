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
// autoResultMap = true：启用 @TableField(typeHandler=JacksonTypeHandler.class) 的结果映射，
// 否则 JSON 类字段（tags、cont_pics）写入正常，但查询(selectById/selectList)时无法反序列化回对象，返回 null
@TableName(value = "ap_article_draft", autoResultMap = true)
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

    /**
     * 文章标签列表（数据库 ap_article_draft.tags 为 JSON 数组字符串，如 ["Java"]）
     * 前端提交标签时同时下发 labels(逗号分隔字符串) 与 tags(数组)，此处以 tags 为准落库；
     * 依赖 @TableName(autoResultMap=true) 保证查询时可反序列化回 List。
     */
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