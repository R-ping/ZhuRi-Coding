package com.zhuri.coding.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.dtos.LoginResultVo;
import com.zhuri.coding.model.user.dtos.SocialAuthDto;
import com.zhuri.coding.model.user.dtos.SocialBindDto;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.model.user.pojos.ApUserSocial;
import com.zhuri.coding.user.mapper.ApUserMapper;
import com.zhuri.coding.user.mapper.ApUserSocialMapper;
import com.zhuri.coding.user.service.LoginCodeService;
import com.zhuri.coding.user.service.TokenService;
import com.zhuri.coding.utils.common.SimpleAesECBUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SocialLoginServiceImpl 单元测试（社交账号认证与绑定）
 *
 * 继承 ServiceImpl：依赖的 baseMapper 通过反射注入私有字段（跨 MP 版本稳定），
 * 其余经 @InjectMocks 注入。getOne/selectOne 依赖的 LambdaQueryWrapper 列解析通过
 * TableInfoHelper.initTableInfo 初始化实体元数据。
 * 覆盖：认证（未绑定/已绑定/用户锁定/参数校验）、绑定（重复/码错/成功）、校验绑定状态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialLoginService 社交认证与绑定")
class SocialLoginServiceImplTest {

    @Mock
    private ApUserSocialMapper apUserSocialMapper;
    @Mock
    private ApUserMapper apUserMapper;
    @Mock
    private TokenService tokenService;
    @Mock
    private LoginCodeService loginCodeService;

    @InjectMocks
    private SocialLoginServiceImpl socialLoginService;

    private static final String PHONE = "13800138000";

    @BeforeEach
    void setUp() throws Exception {
        // ServiceImpl 私有 baseMapper 反射注入，供 getOne 使用
        ReflectionTestUtils.setField(socialLoginService, "baseMapper", apUserSocialMapper);
        // 初始化表元数据，让 LambdaQueryWrapper 能解析列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUserSocial.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ApUser.class);
    }

    private ApUserSocial social(Integer userId) {
        ApUserSocial s = new ApUserSocial();
        s.setUserId(userId);
        s.setPhone(PHONE);
        s.setPlatform("github");
        return s;
    }

    private ApUser user(Integer id, boolean status) {
        ApUser u = new ApUser();
        u.setId(id);
        u.setNickname("昵称");
        u.setPhone(PHONE);
        u.setStatus(status);
        return u;
    }

    private SocialBindDto bindDto(String platform, String platformUid, String phone, String code) {
        SocialBindDto d = new SocialBindDto();
        d.setPlatform(platform);
        d.setPlatformUid(SimpleAesECBUtil.encrypt(platformUid));
        d.setPhone(phone);
        d.setCode(code);
        return d;
    }

    @Nested
    @DisplayName("社交认证")
    class SocialAuth {

        @Test
        @DisplayName("platform/platformUid 缺失 → 参数必填")
        void testBlankParam() {
            SocialAuthDto dto = new SocialAuthDto();
            dto.setPlatform("");
            dto.setPlatformUid(null);

            assertEquals(AppHttpCodeEnum.PARAM_REQUIRE.getCode(),
                    socialLoginService.socialAuth(dto).getCode());
        }

        @Test
        @DisplayName("已绑定且用户有效 → 直接登录返回双Token")
        void testAuthBound() {
            when(apUserSocialMapper.selectOne(any(Wrapper.class))).thenReturn(social(10));
            when(apUserMapper.selectOne(any(Wrapper.class))).thenReturn(user(10, true));
            when(tokenService.generateDualToken(any(), anyString(), any(), any()))
                    .thenReturn(LoginResultVo.builder().status("login").build());

            SocialAuthDto dto = new SocialAuthDto();
            dto.setPlatform("github");
            dto.setPlatformUid("uid-1");
            ResponseResult r = socialLoginService.socialAuth(dto);

            assertNotNull(r.getData());
            assertInstanceOf(LoginResultVo.class, r.getData());
            verify(tokenService).generateDualToken(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("已绑定但用户不存在/锁定 → 数据不存在")
        void testAuthBoundUserInvalid() {
            when(apUserSocialMapper.selectOne(any(Wrapper.class))).thenReturn(social(88));
            when(apUserMapper.selectOne(any(Wrapper.class)))
                    .thenReturn(user(88, false)); // 状态锁定

            SocialAuthDto dto = new SocialAuthDto();
            dto.setPlatform("github");
            dto.setPlatformUid("uid-1");
            ResponseResult r = socialLoginService.socialAuth(dto);

            assertEquals(AppHttpCodeEnum.AP_USER_DATA_NOT_EXIST.getCode(), r.getCode());
        }

        @Test
        @DisplayName("未绑定 → 返回 need_bind 且 platformUid 加密")
        void testAuthUnbound() {
            SocialAuthDto dto = new SocialAuthDto();
            dto.setPlatform("weibo");
            dto.setPlatformUid("new-uid");

            ResponseResult r = socialLoginService.socialAuth(dto);

            LoginResultVo vo = (LoginResultVo) r.getData();
            assertEquals("need_bind", vo.getStatus());
            assertEquals("weibo", vo.getPlatform());
            // 返回的应是加密后的 uid，而非明文
            assertNotNull(vo.getPlatformUid());
            assertNotEquals("new-uid", vo.getPlatformUid());
        }
    }

    @Nested
    @DisplayName("社交绑定")
    class SocialBind {

        @Test
        @DisplayName("社交账号已被他人绑定 → 已绑定错误")
        void testAlreadyBound() {
            // 首次 selectOne（existBind 校验）返回已存在社交关系
            when(apUserSocialMapper.selectOne(any(Wrapper.class))).thenReturn(social(1));

            ResponseResult r = socialLoginService.socialBind(
                    bindDto("github", "uid-x", PHONE, "1234"));

            assertEquals(AppHttpCodeEnum.SOCIAL_ALREADY_BOUND.getCode(), r.getCode());
        }

        @Test
        @DisplayName("该手机号已绑定其他同类型社交账号 → 已绑定其他账号错误")
        void testPhoneBoundOther() {
            // 1参 selectOne（existBind）返回空；2参 selectOne（getOne 校验手机号）返回已绑定其他账号
            when(apUserSocialMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            when(apUserSocialMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(social(2));

            ResponseResult r = socialLoginService.socialBind(
                    bindDto("github", "uid-x", PHONE, "1234"));

            assertEquals(AppHttpCodeEnum.SOCIAL_ACCOUNT_BOUND_OTHER.getCode(), r.getCode());
        }

        @Test
        @DisplayName("验证码错误/缺失 → 验证码错误")
        void testCodeMismatch() {
            when(apUserSocialMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            // 验证码校验失败（错误、已过期或已被消费）
            when(loginCodeService.verifyAndConsume("github", PHONE, "1234")).thenReturn(false);

            ResponseResult r = socialLoginService.socialBind(
                    bindDto("github", "uid-x", PHONE, "1234"));

            assertEquals(AppHttpCodeEnum.LOGIN_CODE_ERROR.getCode(), r.getCode());
        }

        @Test
        @DisplayName("校验通过 → 创建用户与绑定并返回双Token")
        void testBindSuccess() {
            // existBind / 手机号校验 / insertOrUpdate 前的手机号查询 三处 selectOne 均视为无既有绑定
            when(apUserSocialMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            when(loginCodeService.verifyAndConsume("github", PHONE, "1234")).thenReturn(true);
            when(tokenService.generateDualToken(any(), anyString(), any(), any()))
                    .thenReturn(LoginResultVo.builder().status("login").build());

            ResponseResult r = socialLoginService.socialBind(
                    bindDto("github", "uid-x", PHONE, "1234"));

            assertInstanceOf(LoginResultVo.class, r.getData());
            verify(apUserMapper).insert(any(ApUser.class));
            verify(apUserSocialMapper).insert(any(ApUserSocial.class));
        }
    }

    @Nested
    @DisplayName("校验绑定状态")
    class CheckBind {

        @Test
        @DisplayName("tag=bind 且已绑定 → 返回 null")
        void testBindModeBound() {
            // checkSocialBind 内 getOne 走 2参 selectOne(wrapper, throwEx)：返回已存在记录则不再下发验证码
            when(apUserSocialMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(social(5));

            assertNull(socialLoginService.checkSocialBind(PHONE, "github", "bind"));
        }

        @Test
        @DisplayName("未绑定 → 生成验证码并缓存，返回验证码")
        void testUnboundGenerateCode() {
            // 2参 selectOne 未 stub，默认返回 null：视为未绑定，走到验证码生成分支
            String code = socialLoginService.checkSocialBind(PHONE, "github", "bind");

            assertNotNull(code);
            assertEquals(4, code.length());
            verify(loginCodeService).issueCode(eq("github"), eq(PHONE), anyString());
        }
    }
}