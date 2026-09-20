package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("lottery_physical_orders")
public class LotteryPhysicalOrder {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String prizeId;
    private String prizeName;
    private String receiverName;
    private String phone;
    private String address;
    private Integer status;
    private String expressNo;
    private Date expireAt;
    private Date createdAt;
    private Date updatedAt;
}
