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

    /** AI 余额（次）——兼容存量：额度包按次售卖时期的余额，仍可继续消耗 */
    @TableField("balance")
    private Integer balance;

    /**
     * AI token 余额（用量按 token 结算）。
     *
     * <p>与 {@link #balance} 双轨并存：次数余额是历史口径（存量订单与老前端展示），
     * token 余额是新的计费口径 —— 因为各功能 token 成本差 10 倍以上，按次数计费不公平也不可控。
     */
    @TableField("token_balance")
    private Long tokenBalance;

    @TableField("update_time")
    private Date updateTime;
}
