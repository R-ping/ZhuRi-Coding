package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("ap_user_diamond_log")
public class ApUserDiamondLog implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String changeType;
    private Integer changeAmount;
    private Integer balance;
    private String sourceId;
    private LocalDateTime createdAt;
}