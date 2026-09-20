package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("lottery_draw_records")
public class LotteryDrawRecord {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String drawBatchId;
    private Long userId;
    private String prizeId;
    private String prizeName;
    private Integer prizeType;
    private Integer oreAmount;
    private String virtualItemCode;
    private Long physicalOrderId;
    private Integer luckyValueBefore;
    private Integer luckyValueAfter;
    private Integer todayDrawCountAtTime;
    private Integer costOre;
    private Boolean isFree;
    private Date createdAt;
}
