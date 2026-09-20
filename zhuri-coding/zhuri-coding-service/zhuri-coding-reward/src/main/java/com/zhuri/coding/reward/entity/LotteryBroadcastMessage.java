package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("lottery_broadcast_messages")
public class LotteryBroadcastMessage {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String userNickname;
    private String prizeName;
    private Integer prizeType;
    private Date createdAt;
}
