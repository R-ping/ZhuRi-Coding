package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("topic_circle_relation")
public class TopicCircleRelation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableField("topic_id")
    private Long topicId;

    @TableField("circle_id")
    private Long circleId;
}