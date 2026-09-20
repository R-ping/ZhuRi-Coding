package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("user_checkin_state")
public class UserCheckinState {
    @TableId
    private Long userId;
    private Integer continuousDays;
    private Integer periodDay;
    private Date lastCheckinDate;
    private Integer totalCheckinDays;
    private Integer patchCardCount;
    private Date updatedAt;
}
