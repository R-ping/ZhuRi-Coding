package com.heima.model.user.pojos;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("user_score_summary")
public class UserScoreSummary implements Serializable {
    private Long id;
    private Long userId;
    private Date statDate;
    private BigDecimal totalScore;
    private BigDecimal basicScore;
    private BigDecimal activeScore;
    private BigDecimal learnScore;
    private BigDecimal effectScore;
    private BigDecimal specScore;
}