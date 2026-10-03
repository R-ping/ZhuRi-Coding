package com.zhuri.coding.content.controller.v1.coding;

import com.zhuri.coding.content.service.coding.CodingProfileService;
import com.zhuri.coding.model.coding.dtos.CodingProfileSettingDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力档案接口（Coding 延展第二层 · Stage A）
 *
 * <p><b>路径说明</b>：本控制器横跨两个前缀，故不做类级 @RequestMapping：</p>
 * <ul>
 *   <li>私有接口挂 {@code /api/v1/coding/profile/*}（经网关 /content 前缀转发，需登录）；</li>
 *   <li>公开接口挂 {@code /api/v1/user/home/{userId}/ability}，复用个人主页白名单前缀
 *       （AuthorizeFilter 已放行 /content/api/v1/user/home/，匿名可访问、零网关改动）；
 *       携带有效 token 且为本人时返回全量（网关对公开路径仍会注入身份）。</li>
 * </ul>
 */
@Slf4j
@RestController
public class CodingProfileController {

    @Autowired
    private CodingProfileService profileService;

    /**
     * 我的能力档案（本人视角，全量返回）
     * GET /api/v1/coding/profile/me
     */
    @GetMapping("/api/v1/coding/profile/me")
    public ResponseResult me() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return profileService.profile(user.getId(), user.getId());
    }

    /**
     * 公开能力档案（他人主页/分享页；整体未公开时只返回空态）
     * GET /api/v1/user/home/{userId}/ability
     */
    @GetMapping("/api/v1/user/home/{userId}/ability")
    public ResponseResult ability(@PathVariable("userId") Integer userId) {
        ApUser user = AppThreadLocalUtil.getUser();
        return profileService.profile(userId, user == null ? null : user.getId());
    }

    /**
     * 读取档案隐私开关（无记录返回默认值：整体私有、分项公开）
     * GET /api/v1/coding/profile/setting
     */
    @GetMapping("/api/v1/coding/profile/setting")
    public ResponseResult getSetting() {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return profileService.getSetting(user.getId());
    }

    /**
     * 保存档案隐私开关（字段为空表示不修改；首次保存自动建行）
     * PUT /api/v1/coding/profile/setting
     */
    @PutMapping("/api/v1/coding/profile/setting")
    public ResponseResult updateSetting(@RequestBody CodingProfileSettingDTO dto) {
        ApUser user = AppThreadLocalUtil.getUser();
        if (user == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        return profileService.updateSetting(user.getId(), dto);
    }
}