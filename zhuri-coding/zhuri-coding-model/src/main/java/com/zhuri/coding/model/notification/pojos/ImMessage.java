package com.heima.model.notification.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("im_messages")
public class ImMessage implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long senderId;
    private Long receiverId;
    private String content;
    private Integer msgType;         // 1-文本 2-图片
    private Integer status;          // 0-已发送 1-已读
    private Integer isDeletedForSender;
    private Integer isDeletedForReceiver;
    private LocalDateTime createdAt;
}