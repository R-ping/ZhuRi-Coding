package com.heima.reward.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("welfare_goods")
public class WelfareGoods {
    @TableId
    private String id;
    private String name;
    private String description;
    private String imageUrl;
    private Integer type;
    private Integer category;
    private Integer orePrice;
    private Integer originalPrice;
    private String discountTag;
    private Integer stock;
    private Integer totalStock;
    private Integer exchangedCount;
    private Boolean isVirtual;
    private String timeLimitStart;
    private String timeLimitEnd;
    private String timeLimitDesc;
    private String virtualCodeTemplate;
    private Integer status;
    private Integer sortOrder;
    private Date createdAt;
    private Date updatedAt;
}
