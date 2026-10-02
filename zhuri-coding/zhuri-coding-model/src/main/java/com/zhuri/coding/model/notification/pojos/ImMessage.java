package com.zhuri.coding.model.notification.pojos;

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
    /**
     * 客户端去重 ID。由客户端在发送时生成（如 UUID），同一会话内唯一；
     * 为 null 表示这条消息不参与去重（存量数据、未升级的客户端）。
     * 配合 im_messages 的 uk_session_client 使用。
     */
    private String clientId;
    private String content;
    private Integer msgType;         // 1-文本 2-图片
    private Integer status;          // 0-已发送 1-已读
    private Integer isDeletedForSender;
    private Integer isDeletedForReceiver;
    private LocalDateTime createdAt;
}