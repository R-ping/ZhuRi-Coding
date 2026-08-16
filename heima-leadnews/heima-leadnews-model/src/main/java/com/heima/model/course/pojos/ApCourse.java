package com.heima.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ap_course")
public class ApCourse implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("subtitle")
    private String subtitle;

    @TableField("description")
    private String description;

    @TableField("cover_image")
    private String coverImage;

    @TableField("author_id")
    private Integer authorId;

    @TableField("author_name")
    private String authorName;

    @TableField("author_avatar")
    private String authorAvatar;

    @TableField("price")
    private BigDecimal price;

    @TableField("original_price")
    private BigDecimal originalPrice;

    @TableField("status")
    private Byte status;

    @TableField("reason")
    private String reason;

    /** 申报审核拒绝原因 */
    @TableField("apply_reason")
    private String applyReason;

    /** 小册申报内容（JSON：选题/大纲/简介/样章） */
    @TableField("apply_content")
    private String applyContent;

    /** 申报提交时间 */
    @TableField("apply_time")
    private Date applyTime;

    /** 编辑审核时间 */
    @TableField("review_time")
    private Date reviewTime;

    @TableField("category_id")
    private Integer categoryId;

    @TableField("chapter_count")
    private Integer chapterCount;

    @TableField("study_count")
    private Integer studyCount;

    @TableField("estimated_hours")
    private BigDecimal estimatedHours;

    @TableField("published_at")
    private Date publishedAt;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField("version")
    private Integer version;

    @TableField("sales_count")
    private Integer salesCount;

    @TableField("total_revenue")
    private BigDecimal totalRevenue;

    public enum Status {
        NORMAL((byte) 0),
        SUBMIT((byte) 1),
        FAIL((byte) 2),
        OFFLINE((byte) 3),
        /** 申报通过、写作中 */
        WRITING((byte) 4),
        /** 上架待审 */
        REVIEW((byte) 5),
        PUBLISHED((byte) 9);

        byte code;

        Status(byte code) {
            this.code = code;
        }

        public byte getCode() {
            return this.code;
        }
    }
}