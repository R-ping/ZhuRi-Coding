package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.io.Serializable;
import java.math.BigDecimal;
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
@TableName(value = "ap_article", autoResultMap = true)
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
     * 文章简要内容（摘要），用于列表页标题下方展示
     */
    private String summary;

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
     * 文章标签列表（数据库 ap_article.tags 为 JSON 数组字符串，如 ["Java"]）。
     * 发布时来源自草稿 ApArticleDraft.tags；前端展示时以逗号分隔字符串 labels 下发，两者一一对应。
     * 依赖 @TableName(autoResultMap=true) 保证查询时可反序列化回 List。
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
    /**
     * 是否开放评论 1开放 0关闭（创作者中心评论管理）
     */
    @TableField("comment_open")
    private Boolean commentOpen = true;

    /**
     * 打赏人数
     */
    @TableField("tip_count")
    private Integer tipCount = 0;

    /**
     * 打赏总金额
     */
    @TableField("tip_amount")
    private BigDecimal tipAmount = BigDecimal.ZERO;


    private Integer score;

    /**
     * 热度指数（非持久化，查询时动态计算）。
     * <p>由编辑/系统热度分 score 与互动量（浏览/点赞/收藏/评论）综合加权得出，
     * 用于榜单与作者中心的"热门"语义展示，对齐掘金 hot_index。</p>
     */
    @TableField(exist = false)
    private Integer hotIndex;
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
    /** 内容诚信治理：1-疑似AI水文(关闭打赏/不入RAG向量库) */
    @TableField("is_aigc")
    private Integer isAigc;

    /** 内容诚信治理：AI水文疑似分 0-100 */
    @TableField("aigc_score")
    private Integer aigcScore;

    /** 内容诚信治理：检测时间 */
    @TableField("aigc_checked_at")
    private Date aigcCheckedAt;

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
     * 动态计算热度指数：编辑分权重 + 互动加权（浏览低权重，点赞/收藏/评论高权重）。
     */
    public int computeHotIndex() {
        int base = this.score != null ? this.score : 0;
        int v = this.views != null ? this.views : 0;
        int l = this.likes != null ? this.likes : 0;
        int c = this.collection != null ? this.collection : 0;
        int cm = this.comment != null ? this.comment : 0;
        return base * 10 + v + l * 20 + c * 30 + cm * 50;
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
        map.put("id", this.id != null ? String.valueOf(this.id) : "");
        map.put("title", nullSafe(this.title));
        map.put("summary", nullSafe(this.summary));
        map.put("authorId", this.authorId != null ? String.valueOf(this.authorId) : "");
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
        map.put("commentOpen", this.commentOpen != null ? this.commentOpen : true);
        map.put("views", this.views != null ? this.views : "");
        map.put("score", this.score != null ? this.score : "");
        map.put("hotIndex", this.hotIndex != null ? this.hotIndex : computeHotIndex());
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
