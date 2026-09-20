package com.zhuri.coding.model.course.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Getter;

@Data
@TableName("ap_course_order")
public class ApCourseOrder implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Integer userId;

    @TableField("course_id")
    private Long courseId;

    @TableField("original_amount")
    private BigDecimal originalAmount;

    @TableField("discount_amount")
    private BigDecimal discountAmount;

    @TableField("paid_amount")
    private BigDecimal paidAmount;
    @TableField("total_amount")
    private BigDecimal totalAmount;
    @TableField("discount_code")
    private String discountCode;
    @TableField("coupon_item_code")
    private String couponItemCode;
    @TableField("pay_method")
    private PayType payMethod;

    @TableField("status")
    private Integer status;

    @TableField("pay_time")
    private Date payTime;

    @TableField("trade_no")
    private String tradeNo;

    @TableField("refund_trade_no")
    private String refundTradeNo;

    @TableField("refund_time")
    private Date refundTime;

    @TableField("refund_pending")
    private Integer refundPending;

    /**
     * 退款待重试原因（与 refund_pending=1 配合使用）：
     * <ul>
     *   <li>{@code order_closed}：支付成功但订单已关单（并发关单 vs 支付回调竞争）</li>
     *   <li>{@code discount_code_exhausted}：订单使用折扣码但核销失败（并发超卖/已用完）</li>
     *   <li>{@code coupon_consume_failed}：订单使用通用5折券但远程核销失败（reward 不可用）</li>
     * </ul>
     */
    @TableField("refund_pending_reason")
    private String refundPendingReason;

    @TableField("refund_retry_count")
    private Integer refundRetryCount;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    /**
     * 订单状态机：
     * PENDING(待支付) → [去支付原子抢占] → PROCESSING(支付处理中) → [支付成功] → PAID
     * PENDING / PROCESSING → [超时关单] → CANCELLED；PAID → [退款] → REFUNDED
     */
    @Getter
    public enum Status {
        PENDING(0), PAID(1), CANCELLED(2), REFUNDED(3), PROCESSING(4);
        final int code;
        Status(int code) { this.code = code; }
    }

    @Getter
    public enum PayType{
        WEIXING("微信支付"),ZHIFUBAO("支付宝支付"),OTHER("其它");
        final String name;
        PayType(String name) {
            this.name=name;
        }
    }
}