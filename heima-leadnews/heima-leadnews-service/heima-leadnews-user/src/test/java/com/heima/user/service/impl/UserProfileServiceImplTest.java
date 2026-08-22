package com.heima.user.service.impl;

import com.aliyun.oss.OSS;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dto.ProfileUpdateDTO;
import com.heima.model.user.pojos.ApUser;
import com.heima.model.user.pojos.SysTag;
import com.heima.model.user.pojos.UserProfile;
import com.heima.model.user.pojos.UserTagRelation;
import com.heima.model.user.vo.TagGroupVO;
import com.heima.model.user.vo.UserProfileVO;
import com.heima.user.config.OssConfig;
import com.heima.user.mapper.SysTagMapper;
import com.heima.user.mapper.UserProfileMapper;
import com.heima.user.mapper.UserTagRelationMapper;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserProfileServiceImpl 单元测试（个人资料/标签/头像）
 *
 * 纯 @Service，经 @InjectMocks 注入：
 * - 三个 Mapper（UserProfile/SysTag/UserTagRelation）；
 * - OssConfig + OSS 客户端（头像上传，putObject 为 void 直接 mock）。
 * 覆盖 getProfile / updateProfile / uploadAvatar 全部分支与参数校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserProfileService 个人资料")
class UserProfileServiceImplTest {

    @Mock
    private UserProfileMapper userProfileMapper;
    @Mock
    private SysTagMapper sysTagMapper;
    @Mock
    private UserTagRelationMapper userTagRelationMapper;
    @Mock
    private OSS ossClient;

    @InjectMocks
    private UserProfileServiceImpl userProfileService;

    private OssConfig ossConfig;

    private static final Long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        // 用真实配置实例，便于断言生成的 URL，避免复杂 mock
        ossConfig = new OssConfig();
        ossConfig.setHost("https://oss.example.com");
        ossConfig.setBucket("leadnews");
        ossConfig.setDir("material/");
        setField("ossConfig", ossConfig);

        AppThreadLocalUtil.clear();
        ApUser u = new ApUser();
        u.setId(USER_ID.intValue());
        u.setNickname("测试昵称");
        AppThreadLocalUtil.setUser(u);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = UserProfileServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(userProfileService, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 资料查询 ====================

    @Nested
    @DisplayName("获取个人资料")
    class GetProfile {

        @Test
        @DisplayName("未登录 → NEED_LOGIN")
        void testNotLogin() {
            AppThreadLocalUtil.clear();
            ResponseResult r = userProfileService.getProfile();
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
        }

        @Test
        @DisplayName("已有资料：回填 + 标签分组")
        void testWithProfile() {
            UserProfile profile = new UserProfile();
            profile.setUserId(USER_ID);
            profile.setUsername("zhangsan");
            profile.setAvatarUrl("a.png");
            profile.setCareerDirection("后端");
            profile.setPosition("工程师");
            profile.setCompany("x");
            profile.setWebsite("w");
            profile.setBio("b");
            when(userProfileMapper.selectById(USER_ID)).thenReturn(profile);

            UserTagRelation rel = new UserTagRelation();
            rel.setUserId(USER_ID);
            rel.setTagId(1);
            when(userTagRelationMapper.selectList(any())).thenReturn(Collections.singletonList(rel));

            SysTag t1 = tag(1, "c1", "类1", "后端");
            SysTag t2 = tag(2, "c1", "类1", "前端");
            SysTag t3 = tag(3, "c2", "类2", "测试");
            when(sysTagMapper.selectList(any())).thenReturn(Arrays.asList(t1, t2, t3));

            ResponseResult r = userProfileService.getProfile();

            UserProfileVO vo = (UserProfileVO) r.getData();
            assertEquals(USER_ID, vo.getUserId());
            assertEquals("zhangsan", vo.getUsername());
            assertEquals("工程师", vo.getPosition());
            assertEquals(Collections.singletonList(1), vo.getSelectedTagIds());
            // 按 categoryCode 分组，共 2 组
            assertEquals(2, vo.getTagGroups().size());
            TagGroupVO g0 = vo.getTagGroups().get(0);
            assertEquals("c1", g0.getCategoryCode());
            assertEquals("类1", g0.getCategoryName());
            assertEquals(2, g0.getTags().size());
        }

        @Test
        @DisplayName("无资料记录 → 用昵称兜底用户名")
        void testNoProfile() {
            when(userProfileMapper.selectById(USER_ID)).thenReturn(null);
            when(userTagRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(sysTagMapper.selectList(any())).thenReturn(Collections.emptyList());

            ResponseResult r = userProfileService.getProfile();

            UserProfileVO vo = (UserProfileVO) r.getData();
            assertEquals("测试昵称", vo.getUsername());
            assertTrue(vo.getSelectedTagIds().isEmpty());
            assertTrue(vo.getTagGroups().isEmpty());
        }
    }

    // ==================== 资料更新 ====================

    @Nested
    @DisplayName("更新个人资料")
    class UpdateProfile {

        private ProfileUpdateDTO validDto() {
            ProfileUpdateDTO dto = new ProfileUpdateDTO();
            dto.setUsername("nickname");
            dto.setCareerDirection("后端研发");
            dto.setTagIds(Arrays.asList(1, 2));
            return dto;
        }

        @Test
        @DisplayName("未登录 → NEED_LOGIN")
        void testNotLogin() {
            AppThreadLocalUtil.clear();
            ResponseResult r = userProfileService.updateProfile(validDto());
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
        }

        @Test
        @DisplayName("用户名缺失 → 503 用户名不能为空")
        void testBlankUsername() {
            ProfileUpdateDTO dto = validDto();
            dto.setUsername("   ");
            ResponseResult r = userProfileService.updateProfile(dto);
            assertEquals(503, r.getCode());
        }

        @Test
        @DisplayName("用户名长度非法（<5 或 >20）→ 503")
        void testInvalidUsernameLength() {
            ProfileUpdateDTO dto = validDto();
            dto.setUsername("ab");
            assertEquals(503, userProfileService.updateProfile(dto).getCode());

            dto.setUsername("abcdefghijklmnopqrstuvwxyz1234");
            assertEquals(503, userProfileService.updateProfile(dto).getCode());
        }

        @Test
        @DisplayName("职业方向缺失 → 503")
        void testBlankCareerDirection() {
            ProfileUpdateDTO dto = validDto();
            dto.setCareerDirection("  ");
            assertEquals(503, userProfileService.updateProfile(dto).getCode());
        }

        @Test
        @DisplayName("未选标签 → 503 至少选一个")
        void testEmptyTags() {
            ProfileUpdateDTO dto = validDto();
            dto.setTagIds(null);
            assertEquals(503, userProfileService.updateProfile(dto).getCode());

            dto.setTagIds(Collections.emptyList());
            assertEquals(503, userProfileService.updateProfile(dto).getCode());
        }

        @Test
        @DisplayName("校验通过：已有资料走更新，先删后插标签")
        void testUpdateExistingProfile() {
            UserProfile existing = new UserProfile();
            existing.setUserId(USER_ID);
            when(userProfileMapper.selectById(USER_ID)).thenReturn(existing);

            ResponseResult r = userProfileService.updateProfile(validDto());

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            verify(userProfileMapper).updateById(any(UserProfile.class));
            verify(userProfileMapper, never()).insert(any(UserProfile.class));
            verify(userTagRelationMapper).delete(any());
            verify(userTagRelationMapper, times(2)).insert(any(UserTagRelation.class));
        }

        @Test
        @DisplayName("校验通过：无资料记录走插入")
        void testInsertNewProfile() {
            when(userProfileMapper.selectById(USER_ID)).thenReturn(null);

            ResponseResult r = userProfileService.updateProfile(validDto());

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            verify(userProfileMapper).insert(any(UserProfile.class));
            verify(userProfileMapper, never()).updateById(any(UserProfile.class));
        }
    }

    // ==================== 头像上传 ====================

    @Nested
    @DisplayName("上传头像")
    class UploadAvatar {

        private MockMultipartFile image(String contentType, long size) {
            return new MockMultipartFile("file", "a.png", contentType,
                    new byte[(int) size]);
        }

        @Test
        @DisplayName("未登录 → NEED_LOGIN")
        void testNotLogin() {
            AppThreadLocalUtil.clear();
            ResponseResult r = userProfileService.uploadAvatar(image("image/png", 1000));
            assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), r.getCode());
        }

        @Test
        @DisplayName("文件为空 → 503 请选择图片")
        void testEmptyFile() {
            MockMultipartFile empty = new MockMultipartFile("file", "empty.png",
                    "image/png", new byte[0]);
            ResponseResult r = userProfileService.uploadAvatar(empty);
            assertEquals(503, r.getCode());
        }

        @Test
        @DisplayName("类型不合法 → 503 仅支持JPG/PNG/WebP")
        void testInvalidContentType() {
            ResponseResult r = userProfileService.uploadAvatar(image("image/gif", 1000));
            assertEquals(503, r.getCode());
        }

        @Test
        @DisplayName("超过5MB → 503 图片过大")
        void testTooLarge() {
            ResponseResult r = userProfileService.uploadAvatar(image("image/png", 5 * 1024 * 1024 + 1));
            assertEquals(503, r.getCode());
        }

        @Test
        @DisplayName("上传成功：已有资料更新头像URL")
        void testUploadExistingProfile() throws IOException {
            UserProfile existing = new UserProfile();
            existing.setUserId(USER_ID);
            when(userProfileMapper.selectById(USER_ID)).thenReturn(existing);

            ResponseResult r = userProfileService.uploadAvatar(image("image/png", 1000));

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            Map<String, String> data = (Map<String, String>) r.getData();
            assertNotNull(data.get("url"));
            assertTrue(data.get("url").startsWith("https://oss.example.com/material/avatar/"));
            verify(userProfileMapper).updateById(any(UserProfile.class));
            verify(ossClient).putObject(eq("leadnews"), anyString(), any(java.io.InputStream.class));
        }

        @Test
        @DisplayName("上传成功：无资料记录走插入")
        void testUploadNewProfile() throws IOException {
            when(userProfileMapper.selectById(USER_ID)).thenReturn(null);

            ResponseResult r = userProfileService.uploadAvatar(image("image/png", 1000));

            assertEquals(AppHttpCodeEnum.SUCCESS.getCode(), r.getCode());
            verify(userProfileMapper).insert(any(UserProfile.class));
        }

        @Test
        @DisplayName("OSS 抛 IOException → 503 头像上传失败")
        void testUploadIOException() throws IOException {
            org.springframework.web.multipart.MultipartFile file = mock(org.springframework.web.multipart.MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getContentType()).thenReturn("image/png");
            when(file.getSize()).thenReturn(1000L);
            when(file.getOriginalFilename()).thenReturn("a.png");
            when(file.getInputStream()).thenThrow(new IOException("io down"));

            ResponseResult r = userProfileService.uploadAvatar(file);

            assertEquals(503, r.getCode());
        }
    }

    private SysTag tag(Integer id, String code, String cateName, String name) {
        SysTag t = new SysTag();
        t.setId(id);
        t.setCategoryCode(code);
        t.setCategoryName(cateName);
        t.setTagName(name);
        return t;
    }
}