package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("welfare_stock_logs")
public class WelfareStockLog {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String goodsId;
    private Integer changeAmount;
    private String exchangeId;
    private Date createdAt;
}
