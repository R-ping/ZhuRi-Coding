package com.heima.model.notification.pojos;

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
    private String content;      // JSON存储多态数据
    private Integer isRead;      // 0-未读 1-已读
    private LocalDateTime createdAt;
}