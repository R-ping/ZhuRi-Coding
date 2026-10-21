package com.zhuri.coding.notification.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.AutoReplySettingDto;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.notification.service.AutoReplyService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注自动私信：C 端设置接口 + 关注服务的内部投递接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/im")
public class AutoReplyController {

    @Autowired
    private AutoReplyService autoReplyService;

    /** 读取我的自动回复配置（未设置过返回默认值） */
    @GetMapping("/auto-reply")
    public ResponseResult getSetting() {
        return autoReplyService.getSetting(currentUserId());
    }

    /** 保存开关 / 文案 */
    @PutMapping("/auto-reply")
    public ResponseResult saveSetting(@RequestBody AutoReplySettingDto dto) {
        return autoReplyService.saveSetting(currentUserId(), dto);
    }

    /**
     * 内部接口：关注成功后投递自动私信，由关注服务经本地消息表重放调用。
     * <p>不要求登录态 —— 调用方是服务而非用户，身份由入参显式给出。
     */
    @PostMapping("/feign/follow-auto-reply")
    public ResponseResult sendFollowAutoReply(@RequestParam("followId") Long followId,
                                              @RequestParam("followerId") Long followerId,
                                              @RequestParam("followeeId") Long followeeId) {
        if (followId == null || followerId == null || followeeId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        autoReplyService.sendFollowAutoReply(followId, followerId, followeeId);
        return ResponseResult.okResult(null);
    }

    private Long currentUserId() {
        ApUser user = AppThreadLocalUtil.getUser();
        return user != null ? user.getId().longValue() : null;
    }
}
