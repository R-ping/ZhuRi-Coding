package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("sign_records")
public class SignRecord {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Date signDate;
    private Integer awardOre;
    private Boolean isExtra;
    private Date createdAt;
}