package com.zhuri.coding.notification.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.notification.dtos.AutoReplySettingDto;

/**
 * 关注自动私信：配置读写 + 触发投递。
 */
public interface AutoReplyService {

    /** 读取当前用户的自动私信配置（未设置过返回默认值） */
    ResponseResult getSetting(Long userId);

    /** 保存开关 / 文案；文案变更需过内容校验 */
    ResponseResult saveSetting(Long userId, AutoReplySettingDto dto);

    /**
     * 关注成功后投递一条自动私信。
     *
     * <p>幂等键为 {@code followId}（关注关系主键）：同一次关注重复投递只落一条消息。
     * 开关关闭、无可用文案、拉黑、冷却窗口、限速等任一条件不满足时静默跳过。
     *
     * @param followId   关注关系 ID
     * @param followerId 触发方（粉丝）
     * @param followeeId 配置方（被关注者，消息以他的身份发出）
     */
    void sendFollowAutoReply(Long followId, Long followerId, Long followeeId);
}
