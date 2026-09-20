package com.heima.model.column.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_column")
public class ApColumn implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("author_id")
    private Long authorId;

    @TableField("author_name")
    private String authorName;

    @TableField("author_image")
    private String authorImage;

    private String title;

    private String description;

    @TableField("cover_image")
    private String coverImage;

    @TableField("article_count")
    private Integer articleCount = 0;

    @TableField("subscribe_count")
    private Integer subscribeCount = 0;

    private Byte status;

    @TableField("is_deleted")
    private Boolean isDeleted = false;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    public enum Status {
        DRAFT((byte) 0),
        SUBMIT((byte) 1),
        FAIL((byte) 2),
        PUBLISHED((byte) 9);

        byte code;
        Status(byte code) { this.code = code; }
        public byte getCode() { return code; }
    }
}