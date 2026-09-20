package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("user_tag_relation")
public class UserTagRelation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableField("user_id")
    private Long userId;

    @TableField("tag_id")
    private Integer tagId;

    @TableField("rel_type")
    private Integer relType;
}