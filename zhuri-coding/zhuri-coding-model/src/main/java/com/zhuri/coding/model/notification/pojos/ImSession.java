package com.heima.model.notification.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("im_sessions")
public class ImSession implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String sessionKey;       // 小ID_大ID
    private Long user1Id;
    private Long user2Id;
    private String lastMessage;      // 最后消息预览
    private LocalDateTime lastMessageAt;
    private Integer user1UnreadCount;
    private Integer user2UnreadCount;
    private Integer isActive;        // B是否回复过A，0-否 1-是
    private LocalDateTime createdAt;
}