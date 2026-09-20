package com.heima.model.comment.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_course_chapter_comment")
public class ApCourseChapterComment implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("chapter_id")
    private Long chapterId;

    @TableField("user_id")
    private Integer userId;

    @TableField("user_name")
    private String userName;

    @TableField("user_avatar")
    private String userAvatar;

    @TableField("content")
    private String content;

    @TableField("parent_id")
    private Long parentId;

    @TableField("reply_to_uid")
    private Integer replyToUid;

    @TableField("created_time")
    private Date createdTime;
}