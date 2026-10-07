package com.zhuri.coding.user.controller.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.UserProfile;
import com.zhuri.coding.user.admin.LocalAdminRoleResolver;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.mapper.UserProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/user/feign")
@Slf4j
public class UserFeignController {

    /** 单次批量上限：防止调用方一次丢进来几万个 id 拖垮本服务 */
    private static final int MAX_BATCH = 200;

    @Autowired
    private ApUserMapper apUserMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private LocalAdminRoleResolver localAdminRoleResolver;

    /**
     * 获取用户基本信息（供其他服务Feign调用）
     */
    @GetMapping("/basic-info")
    public ResponseResult getBasicInfo(@RequestParam("userId") Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(400, "userId不能为空");
        }
        ApUser user = apUserMapper.selectById(userId);
        if (user == null) {
            return ResponseResult.errorResult(404, "用户不存在");
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("nickname", user.getNickname() != null ? user.getNickname() : "");
        userInfo.put("avatar", user.getImage() != null ? user.getImage() : "");
        return ResponseResult.okResult(userInfo);
    }

    /**
     * 获取用户公开信息（昵称、头像、职位/公司/简介），供作者悬浮卡片等场景 Feign 调用
     */
    @GetMapping("/public-info")
    public ResponseResult getPublicInfo(@RequestParam("userId") Long userId) {
        if (userId == null) {
            return ResponseResult.errorResult(400, "userId不能为空");
        }
        ApUser user = apUserMapper.selectById(userId);
        if (user == null) {
            return ResponseResult.errorResult(404, "用户不存在");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("userId", user.getId());
        info.put("nickname", user.getNickname() != null ? user.getNickname() : "");
        info.put("avatar", user.getImage() != null ? user.getImage() : "");

        // 用户资料（职位/公司/简介）可能未初始化
        UserProfile profile = userProfileMapper.selectById(userId);
        info.put("position", profile != null && profile.getPosition() != null ? profile.getPosition() : "");
        info.put("company", profile != null && profile.getCompany() != null ? profile.getCompany() : "");
        info.put("bio", profile != null && profile.getBio() != null ? profile.getBio() : "");
        return ResponseResult.okResult(info);
    }

    /**
     * 批量获取用户基础信息（昵称、头像），供列表场景消除 N+1。
     *
     * <p>只查 ap_user 一张表、不查 user_profile：批量调用方要的是列表上能显示的那两样，
     * 职位/简介用不到，带上就是每个 id 多一次查询。
     *
     * @return data 为 {@code Map<userId, {nickname, avatar}>}；查不到的 id 不出现在结果里
     */
    @GetMapping("/basic-info/batch")
    public ResponseResult getBasicInfoBatch(@RequestParam("userIds") List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return ResponseResult.okResult(new HashMap<>());
        }
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().limit(MAX_BATCH).toList();
        Map<String, Object> result = new HashMap<>();
        for (ApUser user : apUserMapper.selectBatchIds(ids)) {
            Map<String, Object> info = new HashMap<>();
            info.put("userId", user.getId());
            info.put("nickname", user.getNickname() != null ? user.getNickname() : "");
            info.put("avatar", user.getImage() != null ? user.getImage() : "");
            result.put(String.valueOf(user.getId()), info);
        }
        return ResponseResult.okResult(result);
    }

    /**
     * 批量过滤「有效用户」（status=1，未注销/未锁定）。
     *
     * <p>给"向一批用户批量投递"的场景用（如文章更新后给收藏者发提醒）：投递前把已注销账号剔除，
     * 避免给不存在的用户写站内信。注销是软删（{@code AccountServiceImpl#deleteAccount} 置 status=0），
     * 其收藏等行为数据仍在，所以调用方无法自己判断，必须回用户服务确认。</p>
     *
     * @param userIds 待校验的用户ID列表；服务端去重并限量（超出部分忽略）
     * @return data 为其中有效的用户ID列表（顺序不保证）；查不到的 id 视为无效，不出现在结果里
     */
    @GetMapping("/valid-ids")
    public ResponseResult getValidUserIds(@RequestParam("userIds") List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return ResponseResult.okResult(List.of());
        }
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().limit(MAX_BATCH).toList();
        if (ids.isEmpty()) {
            return ResponseResult.okResult(List.of());
        }
        List<Long> valid = new ArrayList<>();
        for (ApUser user : apUserMapper.selectList(new LambdaQueryWrapper<ApUser>()
                .select(ApUser::getId)
                .eq(ApUser::getStatus, true)
                .in(ApUser::getId, ids))) {
            valid.add(user.getId().longValue());
        }
        return ResponseResult.okResult(valid);
    }

    /**
     * 查询运营账号的运营角色编码列表（运营后台鉴权用）。
     *
     * <p>角色与权限点的映射写在代码里（{@code AdminRole} 枚举），本接口只负责把
     * 「运营账号 → 角色编码」这一事实返回；库中若存在代码认不出的编码也照原样返回，
     * 由调用方按 fail-closed 忽略 —— 在这里过滤会让"配错角色"变得难以排查。</p>
     *
     * <p><b>形参名为 {@code accountId} 而不是 {@code userId}，这是有意的</b>：它必须是
     * {@code ap_admin_account.id}，与 C 端 {@code ap_user.id} 是两个互不相交的空间。
     * 叫 {@code userId} 太容易被顺手塞进一个 C 端 ID，而两类 ID 都是整数、传错没有任何信号。</p>
     *
     * <p><b>复用 {@link LocalAdminRoleResolver} 而不是自己写一次查询</b>：本服务内部的运营鉴权
     * 也要做同一件事，两处各写一遍的话，将来加过滤条件必然只改一侧，表现成
     * "运营后台里能用，但 content 侧鉴权认不出这个人"—— 一个极难定位的不一致。</p>
     *
     * @param accountId 运营账号 ID（{@code ap_admin_account.id}）
     * @return data 为角色编码列表；无角色、ID 不是启用中的运营账号均为空列表
     *         （**空列表等价于"不是运营"，不是故障**）
     */
    @GetMapping("/admin-roles")
    public ResponseResult getAdminRoles(@RequestParam("accountId") Long accountId) {
        if (accountId == null) {
            return ResponseResult.okResult(List.of());
        }
        return ResponseResult.okResult(localAdminRoleResolver.roleCodesOf(accountId.intValue()));
    }
}