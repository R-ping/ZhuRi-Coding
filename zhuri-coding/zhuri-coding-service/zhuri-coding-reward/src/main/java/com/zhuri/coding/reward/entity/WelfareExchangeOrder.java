package com.zhuri.coding.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("welfare_exchange_orders")
public class WelfareExchangeOrder {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String exchangeId;
    private Long userId;
    private String goodsId;
    private String goodsName;
    private Boolean isVirtual;
    private Integer oreCost;
    private String receiverName;
    private String phone;
    private String address;
    private String remark;
    private String virtualCode;
    private Integer status;
    private String expressNo;
    /** 客户端幂等号：同一用户同一 key 只成一单（唯一索引 uk_user_idem），未携带时为 null */
    private String idempotencyKey;
    private Date addressExpireAt;
    private Date createdAt;
    private Date updatedAt;
}
