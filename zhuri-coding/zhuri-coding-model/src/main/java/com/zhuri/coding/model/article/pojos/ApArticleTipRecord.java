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
 * 文章打赏流水表（公开感谢名单）
 */
@Data
@TableName("ap_article_tip_record")
public class ApArticleTipRecord implements Serializable {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联订单号 */
    @TableField("order_no")
    private String orderNo;

    /** 打赏人用户ID */
    @TableField("user_id")
    private Integer userId;

    /** 打赏人昵称 */
    @TableField("nick_name")
    private String nickName;

    /** 打赏人头像 */
    private String avatar;

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

    /** 打赏时间 */
    @TableField("created_time")
    private Date createdTime;
}
