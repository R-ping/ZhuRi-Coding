package com.heima.model.ai.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 额度包充值订单（out_trade_no 前缀 "ai"，支付回调按前缀分发到本服务）
 */
@Data
@TableName("ap_ai_topup_order")
public class AiTopupOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务订单号前缀（支付宝 out_trade_no 也用它，回调据此分发） */
    public static final String ORDER_PREFIX = "ai";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PAID = 1;
    public static final int STATUS_CLOSED = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Integer userId;

    @TableField("package_code")
    private String packageCode;

    @TableField("amount_fen")
    private Integer amountFen;

    @TableField("quota_added")
    private Integer quotaAdded;

    @TableField("status")
    private Integer status;

    @TableField("pay_trade_no")
    private String payTradeNo;

    @TableField("pay_time")
    private Date payTime;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;
}
