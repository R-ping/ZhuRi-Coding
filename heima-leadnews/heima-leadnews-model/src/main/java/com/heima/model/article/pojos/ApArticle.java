package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * <p>
 * 文章信息表，存储已发布的文章
 * </p>
 *
 * @author itheima
 */

@Data
@TableName("ap_article")
@SuperBuilder
@NoArgsConstructor       // 新增 — 保证 new ArticleDto() 能用
@AllArgsConstructor      // 新增
public class ApArticle implements Serializable {

    @TableId(value = "id",type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 作者id
     */
    @TableField("author_id")
    private Long authorId;

    /**
     * 作者名称
     */
    @TableField("author_name")
    private String authorName;

    /**
     * 频道id
     */
    @TableField("channel_id")
    private Integer channelId;

    /**
     * 频道名称
     */
    @TableField("channel_name")
    private String channelName;

    /**
     * 文章布局（封面）  1 无图文章
     *     2 有图文章
     */
    private Byte layout;

    /**
     * 文章标记  0 普通文章   1 热点文章   2 置顶文章   3 精品文章   4 大V 文章
     */
    private Byte flag;

    /**
     * 文章封面图片
     */
    private String coverImage;

    /**
     * 专栏ID
     */
    @TableField("column_id")
    private Long columnId;

    /**
     * 标签
     * 前端给 labels:["标签1","标签2"]
     * 数据库存 labels:"标签1,标签2"
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 点赞数量
     */
    private Integer likes;

    /**
     * 收藏数量
     */
    private Integer collection;

    /**
     * 评论数量
     */
    private Integer comment;

    /**
     * 阅读数量
     */
    private Integer views;
    private Integer score;
    /**
     * 省市
     */
    @TableField("province_id")
    private Integer provinceId;

    /**
     * 市区
     */
    @TableField("city_id")
    private Integer cityId;

    /**
     * 区县
     */
    @TableField("county_id")
    private Integer countyId;

    /**
     * 创建时间
     */
    @TableField("created_time")
    private Date createdTime;

    /**
     * 发布时间
     */
    @TableField("publish_time")
    private Date publishTime;

    /**
     * 同步状态
     */
    @TableField("sync_status")
    private Boolean syncStatus;

    /**
     * 来源
     */
    private Boolean origin;

    /**
     * 静态页面地址
     */
    @TableField("static_url")
    private String staticUrl;

    /**
     * 审核状态  0:草稿  1:提交审核  2:审核失败  9:已发布
     */
    private Byte status;

    /**
     * 审核拒绝理由
     */
    private String reason;

    /**
     * 作者头像
     */
    @TableField("author_image")
    private String authorImage;

    /**
     * 是否删除 0 未删除 1 已删除
     */
    @TableField("is_deleted")
    private Boolean isDeleted = false;
    /**
     * 内容里嵌入的图片列表
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ApArticleDraft.ContPic> contPics;
    @Data
    public static class ContPic {
        private String picUri;
        private String picUrl;
    }
    /**
     * 审核状态枚举
     * SUBMIT提交（审核中）,
     * FAIL失败（未通过）,
     * PUBLISHED（已发布）
     */
    public enum Status {
        DRAFT((byte) 0),
        SUBMIT((byte) 1),
        FAIL((byte) 2),
        PUBLISHED((byte) 9);

        byte code;
        Status(byte code) { this.code = code; }
        public byte getCode() { return code; }
    }

    // ==================== 辅助方法 ====================

    /**
     * 是否为已发布状态
     */
    public boolean isPublished() {
        return Status.PUBLISHED.getCode() == this.status;
    }

    /**
     * 是否为草稿状态
     */
    public boolean isDraft() {
        return this.status == null || Status.DRAFT.getCode() == this.status;
    }

    /**
     * 是否为审核中状态
     */
    public boolean isInReview() {
        return Status.SUBMIT.getCode() == this.status;
    }

    /**
     * 是否已删除
     */
    public boolean isDeletedArticle() {
        return Boolean.TRUE.equals(this.isDeleted);
    }

    /**
     * 将文章对象转为 null-safe 的 Map，所有 null 字段统一转为空字符串 ""
     */
    public Map<String, Object> nullSafeToMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", this.id);
        map.put("title", nullSafe(this.title));
        map.put("authorId", this.authorId != null ? this.authorId : "");
        map.put("authorName", nullSafe(this.authorName));
        map.put("channelId", this.channelId != null ? this.channelId : "");
        map.put("channelName", nullSafe(this.channelName));
        map.put("layout", this.layout != null ? this.layout : "");
        map.put("flag", this.flag != null ? this.flag : "");
        map.put("coverImage", nullSafe(this.coverImage));
        map.put("columnId", this.columnId != null ? this.columnId : "");
        map.put("tags", this.tags != null ? this.tags : "");
        map.put("likes", this.likes != null ? this.likes : "");
        map.put("collection", this.collection != null ? this.collection : "");
        map.put("comment", this.comment != null ? this.comment : "");
        map.put("views", this.views != null ? this.views : "");
        map.put("score", this.score != null ? this.score : "");
        map.put("provinceId", this.provinceId != null ? this.provinceId : "");
        map.put("cityId", this.cityId != null ? this.cityId : "");
        map.put("countyId", this.countyId != null ? this.countyId : "");
        map.put("createdTime", this.createdTime != null ? this.createdTime : "");
        map.put("publishTime", this.publishTime != null ? this.publishTime : "");
        map.put("syncStatus", this.syncStatus != null ? this.syncStatus : "");
        map.put("origin", this.origin != null ? this.origin : "");
        map.put("staticUrl", nullSafe(this.staticUrl));
        map.put("status", this.status != null ? this.status : "");
        map.put("reason", nullSafe(this.reason));
        map.put("authorImage", nullSafe(this.authorImage));
        map.put("isDeleted", this.isDeleted != null ? this.isDeleted : "");
        return map;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
