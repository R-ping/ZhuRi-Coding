package com.zhuri.coding.model.notification.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("notifications")
public class Notification implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer type;        // 1-评论 2-赞/收藏 3-粉丝 4-系统
    private String sourceId;     // 触发源ID
    /**
     * 聚合键，格式 {@code type:sourceId}。为空表示这条通知不参与聚合（一行一事件）。
     *
     * <p>评论与系统通知刻意留空：用户在意"说了什么"，把多条评论压成"3 人评论了你"
     * 等于把内容丢了。留空还能顺带享受 MySQL 唯一键允许多个 NULL 的特性——
     * uk_agg 只对填了值的行生效。
     */
    private String aggKey;
    private String content;      // JSON存储多态数据
    /** 本行合并了多少个事件。不聚合的行恒为 1。 */
    private Integer aggCount;
    /**
     * 最后一次事件时间。列表排序与游标用它而不是 created_at：
     * 聚合行被新事件顶起来时 created_at 不变，只有它能让这行浮到列表顶部。
     */
    private LocalDateTime lastEventAt;
    private Integer isRead;      // 0-未读 1-已读
    private LocalDateTime createdAt;
}