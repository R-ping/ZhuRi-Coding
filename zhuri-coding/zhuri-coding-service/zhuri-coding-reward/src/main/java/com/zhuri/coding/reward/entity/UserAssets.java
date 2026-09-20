package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("user_assets")
public class UserAssets {
    @TableId
    private Long userId;
    private Integer oreBalance;
    private Integer frozenOre;
    private Integer luckyValue;
    private Date updatedAt;
    private Date createdAt;
}
