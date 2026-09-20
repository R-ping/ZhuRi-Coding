package com.zhuri.coding.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

/**
 * 用户虚拟道具持有表
 * <p>
 * 用途：抽奖获得的通用虚拟道具（如课程5折券）入账后在这里记录持有数量，下单时核销。
 * 主键：自增ID
 * 唯一键：uk_user_item → 同一个用户同一个道具一个记录，方便计数
 */
@Data
@TableName("user_virtual_assets")
public class UserVirtualAsset {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 虚拟道具代码（如 course50 = 课程5折券）
     */
    private String itemCode;

    /**
     * 道具名称
     */
    private String itemName;

    /**
     * 当前持有数量
     */
    private Integer quantity;

    /**
     * 来源：lottery-抽奖获得
     */
    private String source;

    private Date createdAt;

    private Date updatedAt;
}
