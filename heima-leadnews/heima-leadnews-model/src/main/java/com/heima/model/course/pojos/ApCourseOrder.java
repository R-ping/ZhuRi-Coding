package com.heima.model.course.pojos;

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
    @TableField("pay_method")
    private PayType payMethod;

    @TableField("status")
    private Integer status;

    @TableField("pay_time")
    private Date payTime;

    @TableField("trade_no")
    private String tradeNo;

    @TableField("created_time")
    private Date createdTime;

    @TableField("updated_time")
    private Date updatedTime;

    @Getter
    public enum Status {
        PENDING(0), PAID(1), CANCELLED(2), REFUNDED(3);
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