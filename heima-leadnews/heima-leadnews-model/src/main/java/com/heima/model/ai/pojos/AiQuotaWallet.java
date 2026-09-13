package com.heima.model.ai.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 额度钱包（按用户单行，消费优先于每日免费）
 */
@Data
@TableName("ap_ai_wallet")
public class AiQuotaWallet implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.INPUT)
    private Integer userId;

    /** AI 余额（次） */
    @TableField("balance")
    private Integer balance;

    @TableField("update_time")
    private Date updateTime;
}
