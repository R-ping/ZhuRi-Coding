package com.heima.model.level.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("ap_level_config")
public class ApLevelConfig implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("level_type")
    private Integer levelType;

    @TableField("level_value")
    private Integer levelValue;

    @TableField("min_score")
    private Integer minScore;

    @TableField("max_score")
    private Integer maxScore;

    @TableField("title")
    private String title;

    @TableField("icon_url")
    private String iconUrl;

    @TableField("description")
    private String description;

    /**
     * 等级升级钻石奖励数量
     */
    @TableField("diamond_reward")
    private Integer diamondReward;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;
}