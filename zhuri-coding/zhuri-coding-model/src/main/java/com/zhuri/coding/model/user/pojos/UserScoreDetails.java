package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("user_score_details")
public class UserScoreDetails implements Serializable {
    private Long id;
    private Long userId;
    private Integer category;
    private String actionCode;
    private String actionDesc;
    private BigDecimal score;
    private String bizId;
    private Date createdAt;
}