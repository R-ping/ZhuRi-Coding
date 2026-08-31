package com.heima.user.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.common.constants.ArticleConstants;
import com.heima.common.redis.CacheService;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dtos.LoginDto;
import com.heima.model.user.dtos.LoginResultVo;
import com.heima.model.user.pojos.ApUser;
import com.heima.user.mapper.ApUserMapper;
import com.heima.user.service.ApUserService;
import com.heima.user.service.TokenService;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class ApUserServiceImpl extends ServiceImpl<ApUserMapper, ApUser> implements ApUserService {

    @Autowired
    private TokenService tokenService;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private CacheService cacheService;

    /**
     * 登录功能（使用BCrypt + 双Token）
     */

    private ResponseResult phoneOrEmailPassLogin(LoginDto dto, String tag) {

        //1.1 根据手机号查询用户信息
        ApUser dbUser = null;
        if ("phonePass".equals(tag)) {
            dbUser = getOne(Wrappers.<ApUser>lambdaQuery().eq(ApUser::getPhone, dto.getPhoneOrEmail()));
        } else if ("emailPass".equals(tag)) {
            dbUser = getOne(Wrappers.<ApUser>lambdaQuery().eq(ApUser::getEmail, dto.getPhoneOrEmail()));
        }
        if (dbUser == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "用户信息不存在");
        }
        //1.2 比对密码（BCrypt）
        if (!passwordEncoder.matches(dto.getPassword(), dbUser.getPassword())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR);
        }
        //1.3 生成双token并返回
        LoginResultVo result = tokenService.generateDualToken(
            dbUser.getId(), dbUser.getNickname(), dbUser.getPhone(), dbUser.getImage());
        return ResponseResult.okResult(result);

    }

    @Override
    public ResponseResult   allLoginAuth(LoginDto dto, String tag) {
        // 手机号/邮箱 + 密码
        if ("phonePass".equals(tag) || "emailPass".equals(tag)) {
            return phoneOrEmailPassLogin(dto, tag);
        }
        // 手机号验证码登录/注册
        return phoneCodeLogin(dto);
    }

    @Override
    public ResponseResult searchUser(String keyword, Integer page, Integer size) {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 10 : Math.min(size, 50);

        IPage<ApUser> iPage = new Page<>(safePage, safeSize);
        LambdaQueryWrapper<ApUser> wrapper = new LambdaQueryWrapper<>();
        // 仅查询状态正常(1)的用户，锁定/禁用账号不展示
        wrapper.eq(ApUser::getStatus, true);
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(ApUser::getNickname, keyword.trim());
        }
        wrapper.orderByDesc(ApUser::getCreatedTime);

        IPage<ApUser> resultPage = page(iPage, wrapper);

        // 组装用户搜索字段（绝不返回 password/phone/email 等敏感字段），title=昵称对齐前端展示
        List<Map<String, Object>> list = new ArrayList<>();
        for (ApUser user : resultPage.getRecords()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", user.getId());
            item.put("title", user.getNickname());
            item.put("name", user.getNickname());
            item.put("authorId", user.getId());
            item.put("authorName", user.getNickname());
            item.put("authorAvatar", user.getImage());
            list.add(item);
        }
        return ResponseResult.okResult(list);
    }

    private ResponseResult phoneCodeLogin(LoginDto dto) {
        String phone = dto.getPhoneOrEmail();
        String key = "socialBind:" + dto.getPlatform() +":"+ phone;
        String cacheCode = cacheService.get(key);
        if (cacheCode == null || !cacheCode.equals(dto.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.LOGIN_CODE_ERROR);
        }
        ApUser dbUser = getOne(Wrappers.<ApUser>lambdaQuery().eq(ApUser::getPhone, dto.getPhoneOrEmail()));
        if (dbUser == null) {
            log.info("用户{}不存在，需要注册", phone);
            dbUser = randomUser(phone);
            save(dbUser);
        }
        LoginResultVo result = tokenService.generateDualToken(
            dbUser.getId(), dbUser.getNickname(), dbUser.getPhone(), dbUser.getImage());
        return ResponseResult.okResult(result);

    }

    /**
     * 生成随机昵称，随机头像（前端放了10张图，后端随机给名）
     */
    private ApUser randomUser(String phone) {
        ApUser apUser = new ApUser();
        apUser.setPhone(phone);
        // “用户”+6位随机数
        String nickname = "用户" + RandomUtil.randomNumbers(6);
        apUser.setNickname(nickname);
        apUser.setImage(ArticleConstants.OSS_AVATAR_URLS[RandomUtil.randomInt(0, 10)]);
        apUser.setCreatedTime(new Date());
        return apUser;
    }

}
