package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("lottery_daily_state")
public class LotteryDailyState {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Date statDate;
    private Integer drawCount;
    private Boolean freeUsed;
}
