package com.heima.user.service.impl;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dtos.LoginResultVo;
import com.heima.model.user.dtos.SocialAuthDto;
import com.heima.model.user.dtos.SocialBindDto;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.ApUserSocial;
import com.heima.user.mapper.ApUserMapper;
import com.heima.user.mapper.ApUserSocialMapper;
import com.heima.user.service.LoginCodeService;
import com.heima.user.service.SocialLoginService;
import com.heima.user.service.TokenService;
import com.heima.utils.common.SimpleAesECBUtil;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 社交登录核心业务实现
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class SocialLoginServiceImpl extends ServiceImpl<ApUserSocialMapper, ApUserSocial>
    implements SocialLoginService {

    @Autowired
    private ApUserSocialMapper apUserSocialMapper;
    @Autowired
    private ApUserMapper apUserMapper;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private LoginCodeService loginCodeService;

    @Override
    public ResponseResult socialAuth(SocialAuthDto dto) {
        log.info("=== 进行社交登录认证 === platform={}, platformUid={}", dto.getPlatform(), dto.getPlatformUid());

        // 1. 参数校验
        if (StringUtils.isBlank(dto.getPlatform()) || StringUtils.isBlank(dto.getPlatformUid())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_REQUIRE, "platform和platformUid不能为空");
        }
        String platform = dto.getPlatform();
        // 2. 查询 ap_user_social：是否已绑定
        ApUserSocial social = apUserSocialMapper.selectOne(
            Wrappers.<ApUserSocial>lambdaQuery()
                .eq(platform.equals("github"), ApUserSocial::getGitUid, dto.getPlatformUid())
                .eq(platform.equals("weibo"), ApUserSocial::getWeiboUid, dto.getPlatformUid())
                .like(ApUserSocial::getPlatform,platform ));
        if (social != null) {
            ApUser user = apUserMapper.selectOne(Wrappers.<ApUser>lambdaQuery()
                .eq(ApUser::getId, social.getUserId()));
            if (user == null || !user.getStatus()) {
                log.warn("用户不存在或已锁定: userId={}", social.getUserId());
                return ResponseResult.errorResult(AppHttpCodeEnum.AP_USER_DATA_NOT_EXIST);
            }
            // === 已绑定 → 直接登录 ===
            LoginResultVo result = tokenService.generateDualToken(
                user.getId(), user.getNickname(), user.getPhone(), user.getImage());
            log.info("社交登录成功(已绑定): userId={}, platform={}", user.getId(), dto.getPlatform());
            return ResponseResult.okResult(result);
        }
        // === 未绑定 ===
        // platformUid需要加密一下，之后进行socialBing时还需解密
        String platformUid = dto.getPlatformUid();
        String encryptedPlatformUid = SimpleAesECBUtil.encrypt(platformUid);
        LoginResultVo result = LoginResultVo.builder()
            .status("need_bind")
            .platform(dto.getPlatform())
            .platformUid(encryptedPlatformUid)
            .build();

        log.info("社交登录(新用户): platform={}, platformUid={}",
            dto.getPlatform(), dto.getPlatformUid());
        return ResponseResult.okResult(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseResult socialBind(SocialBindDto dto) {
        String platform = dto.getPlatform();
        String platformUid = dto.getPlatformUid();
        platformUid = SimpleAesECBUtil.decrypt(platformUid);
        // 2. 校验社交账号是否已被绑定，一个社交账号扫多码进行登录时，但手机号不同，在绑定时稍慢的用户会判断不通过
        ApUserSocial existBind = apUserSocialMapper.selectOne(
            Wrappers.<ApUserSocial>lambdaQuery()
                .eq(platform.equals("github"), ApUserSocial::getGitUid, platformUid)
                .eq(platform.equals("weibo"), ApUserSocial::getWeiboUid, platformUid)
                .like(ApUserSocial::getPlatform,platform ));
        if (existBind != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SOCIAL_ALREADY_BOUND);
        }
        // 校验该用户是否已绑定过同类型其他社交账号，一个社交账号扫多码进行登录时，但手机号相同，在绑定时稍慢的用户会判断不通过
        ApUserSocial userSocial = getOne(Wrappers.<ApUserSocial>lambdaQuery()
            .eq(ApUserSocial::getPhone, dto.getPhone())
            .like(ApUserSocial::getPlatform, platform));
        if (userSocial != null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SOCIAL_ACCOUNT_BOUND_OTHER);
        }
        // 3. 校验并一次性消费验证码：通过后立即失效，防止同一验证码被重放
        if (!loginCodeService.verifyAndConsume(dto.getPlatform(), dto.getPhone(), dto.getCode())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.LOGIN_CODE_ERROR);
        }
        ApUser apUser = randomUser(dto);
        apUserMapper.insert(apUser);
        insertOrUpdateSocialBean(dto, apUser);

        // 8. 生成双Token
        LoginResultVo result = tokenService.generateDualToken(
            apUser.getId(), apUser.getNickname(), apUser.getPhone(), apUser.getImage());
        log.info("社交绑定成功: userId={}, platform={}", apUser.getId(), dto.getPlatform());
        return ResponseResult.okResult(result);
    }


    /**
     * 生成随机昵称，随机头像（前端放了10张图，后端随机给名）
     */
    private ApUser randomUser(SocialBindDto dto) {
        ApUser apUser = new ApUser();
        apUser.setPhone(dto.getPhone());
        // “用户”+6位随机数
        String nickname = "用户" + RandomUtil.randomNumbers(6);
        apUser.setNickname(nickname);
        apUser.setImage("avatar_head_" + RandomUtil.randomInt(1, 10));
        apUser.setCreatedTime(new Date());
        return apUser;
    }
    private void insertOrUpdateSocialBean(SocialBindDto dto, ApUser apUser) {
        // 5. 创建社交绑定
        String platform = dto.getPlatform();
        String platformUid = dto.getPlatformUid();
        ApUserSocial social = new ApUserSocial();
        social.setUserId(apUser.getId());
        social.setPhone(apUser.getPhone());
        social.setPlatform(platform);
        if (platform.equals("github")) {
            social.setGitUid(platformUid);
        } else if (platform.equals("weibo")) {
            social.setWeiboUid(platformUid);
        } else {
            social.setOpenId(platformUid);
        }
        social.setCreatedTime(new Date());
        // 该手机号，是否绑定过社交账号
        ApUserSocial userSocial = getOne(Wrappers.<ApUserSocial>lambdaQuery()
            .eq(ApUserSocial::getPhone, dto.getPhone()));
        if (userSocial != null) {// 不包含该次绑定的社交类型
            social.setPlatform(userSocial.getPlatform() + ";" + platform);
            apUserSocialMapper.updateById(social);
        } else {
            apUserSocialMapper.insert(social);
        }
    }

    /**
     * 验证手机号是否已绑定该类型社交账号
     */
    @Override
    public String checkSocialBind(String phone, String platform, String tag) {
        if (tag.equals("bind")) {
            ApUserSocial userSocial = getOne(
                Wrappers.<ApUserSocial>lambdaQuery()
                    .eq(ApUserSocial::getPhone, phone)
                    .like(ApUserSocial::getPlatform, platform));
            if (ObjUtil.isNotNull(userSocial)) {
                return null;
            }
        }
        // 4位随机数
        String code = RandomUtil.randomString(4);
        loginCodeService.issueCode(platform, phone, code);
        return code;
    }


}
