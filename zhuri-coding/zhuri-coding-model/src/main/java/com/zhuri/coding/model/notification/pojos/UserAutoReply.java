package com.zhuri.coding.model.notification.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户关注自动私信配置。
 *
 * <p>独立于用户资料表：资料表是个人主页的高频读表，而本表带审核状态与版本号，
 * 读写频率、变更语义都不同，混在一起会互相拖累。
 */
@Data
@TableName("ap_user_auto_reply")
public class UserAutoReply implements Serializable {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 1-开启 0-关闭 */
    private Integer enabled;

    /** 用户当前编辑的文案（可能尚未过审） */
    private String content;

    /** 文案版本号，每次内容变更 +1 */
    private Integer contentVersion;

    /** 0-待审 1-通过 2-驳回 */
    private Integer auditStatus;

    private String auditReason;

    /**
     * 当前生效文案（上一版已过审内容）。
     * <p>新文案审核中或驳回时继续用这一条，避免「改一次文案功能就停摆」。
     */
    private String effectiveContent;

    private LocalDateTime lastSentAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
