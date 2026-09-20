package com.zhuri.coding.model.article.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * 文章打赏订单表
 */
@Data
@TableName("ap_article_tip_order")
public class ApArticleTipOrder implements Serializable {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单号 */
    @TableField("order_no")
    private String orderNo;

    /** 打赏人用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 文章ID */
    @TableField("article_id")
    private Long articleId;

    /** 作者用户ID */
    @TableField("author_id")
    private Integer authorId;

    /** 打赏金额 */
    private BigDecimal amount;

    /** 打赏留言 */
    private String message;

    /** 状态: 0-待支付 1-已支付 */
    private Integer status;

    /** 支付宝交易号 */
    @TableField("trade_no")
    private String tradeNo;

    /** 支付时间 */
    @TableField("pay_time")
    private Date payTime;

    /** 创建时间 */
    @TableField("created_time")
    private Date createdTime;

    /** 更新时间 */
    @TableField("updated_time")
    private Date updatedTime;

    /** 订单状态枚举 */
    public enum Status {
        PENDING(0), PAID(1);
        final int code;
        Status(int code) { this.code = code; }
        public int getCode() { return code; }
    }
}
