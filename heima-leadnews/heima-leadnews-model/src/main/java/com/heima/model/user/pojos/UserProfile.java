package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("user_profile")
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId("user_id")
    private Long userId;

    @TableField("username")
    private String username;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("career_start_date")
    private Date careerStartDate;

    @TableField("career_direction")
    private String careerDirection;

    @TableField("position")
    private String position;

    @TableField("company")
    private String company;

    @TableField("website")
    private String website;

    @TableField("bio")
    private String bio;

    /** 地区，对齐掘金 city */
    @TableField("region")
    private String region;

    /** 学历/学校，对齐掘金 education */
    @TableField("education")
    private String education;

    /** 技术标签（JSON 数组字符串），对齐掘金 skills */
    @TableField("skills")
    private String skills;

    /** 等级快照，对齐掘金 level（逐力值/逐友等级） */
    @TableField("level")
    private String level;

    @TableField("update_time")
    private Date updateTime;

    @TableField("privacy_message")
    private Integer privacyMessage;
}