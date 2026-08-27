package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("lottery_prize_pool")
public class LotteryPrizePool {
    @TableId
    private String id;
    private String name;
    private Integer type;
    private String iconUrl;
    private BigDecimal probability;
    private Integer minOre;
    private Integer maxOre;
    private String virtualItemCode;
    private BigDecimal discountRate;
    /** 实物奖品总库存（-1=不限量，0=已售罄，>0=剩余件数；type=3 实物时生效，null 视为不限量） */
    private Integer totalStock;
    private Integer unlockRequiredDraws;
    private Boolean isPhysical;
    private Integer sortOrder;
    private Integer status;
}
