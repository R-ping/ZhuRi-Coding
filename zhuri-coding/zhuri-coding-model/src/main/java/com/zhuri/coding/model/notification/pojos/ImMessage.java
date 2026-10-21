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
    /**
     * 发送方类型：1-真实用户 2-系统以该用户身份代发（关注自动私信）。
     * 与 msgType 正交：msgType 说「内容是什么形态」，本字段说「这条消息谁产生的」。
     */
    private Integer senderType;
    /**
     * 来源枚举，普通私信为 null。当前仅 FOLLOW_AUTO_REPLY（关注自动私信）。
     */
    private String sourceType;
    /**
     * 来源业务主键，关注自动私信存关注关系 ID；与 sourceType 组成唯一索引，做投递幂等的数据库兜底。
     */
    private Long sourceId;
    private Integer status;          // 0-已发送 1-已读
    private Integer isDeletedForSender;
    private Integer isDeletedForReceiver;
    private LocalDateTime createdAt;
}