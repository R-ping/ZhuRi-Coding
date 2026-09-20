package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dto.BlockDTO;
import com.heima.model.user.dto.PasswordUpdateDTO;
import com.heima.model.user.dto.PrivacyMessageDTO;
import com.heima.model.user.dto.ProfileUpdateDTO;
import com.heima.user.service.AccountService;
import com.heima.user.service.BlockService;
import com.heima.user.service.TagSubscribeService;
import com.heima.user.service.UserProfileService;
import com.heima.user.service.UserStatisticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 薄委托型 Controller 单元测试（服务转发与参数透传）
 *
 * BlockController / AccountController / UserProfileController / UserStatisticsController /
 * TagSubscribeController 均为"参数→service→结果"的薄封装，逐一验证方法委托正确。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("薄委托型控制器")
class UserControllerDelegationTest {

    @Mock
    private BlockService blockService;
    @Mock
    private AccountService accountService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private UserStatisticsService userStatisticsService;
    @Mock
    private TagSubscribeService tagSubscribeService;

    @InjectMocks
    private BlockController blockController;
    @InjectMocks
    private AccountController accountController;
    @InjectMocks
    private UserProfileController userProfileController;
    @InjectMocks
    private UserStatisticsController userStatisticsController;
    @InjectMocks
    private TagSubscribeController tagSubscribeController;

    private static final ResponseResult OK = ResponseResult.okResult(null);

    @Nested
    @DisplayName("BlockController 拉黑管理")
    class Block {
        @Test
        @DisplayName("查询/新增/删除均委托 service")
        void testDelegate() {
            when(blockService.getBlocks(1, 2, 10)).thenReturn(OK);
            when(blockService.addBlock(any(BlockDTO.class))).thenReturn(OK);
            when(blockService.removeBlock(5L)).thenReturn(OK);

            blockController.getBlocks(1, 2, 10);
            blockController.addBlock(new BlockDTO());
            blockController.removeBlock(5L);

            verify(blockService).getBlocks(1, 2, 10);
            verify(blockService).addBlock(any(BlockDTO.class));
            verify(blockService).removeBlock(5L);
        }
    }

    @Nested
    @DisplayName("AccountController 账号管理")
    class Account {
        @Test
        @DisplayName("绑定/密码/注销/隐私均委托 service")
        void testDelegate() {
            when(accountService.getBindings()).thenReturn(OK);
            when(accountService.updatePassword(any(PasswordUpdateDTO.class))).thenReturn(OK);
            when(accountService.deleteAccount()).thenReturn(OK);
            when(accountService.updatePrivacyMessage(any(PrivacyMessageDTO.class))).thenReturn(OK);

            accountController.getBindings();
            accountController.updatePassword(new PasswordUpdateDTO());
            accountController.deleteAccount();
            accountController.updatePrivacyMessage(new PrivacyMessageDTO());

            verify(accountService).getBindings();
            verify(accountService).updatePassword(any(PasswordUpdateDTO.class));
            verify(accountService).deleteAccount();
            verify(accountService).updatePrivacyMessage(any(PrivacyMessageDTO.class));
        }
    }

    @Nested
    @DisplayName("UserProfileController 个人资料")
    class Profile {
        @Test
        @DisplayName("资料查询/更新/头像上传均委托 service")
        void testDelegate() {
            when(userProfileService.getProfile()).thenReturn(OK);
            when(userProfileService.updateProfile(any(ProfileUpdateDTO.class))).thenReturn(OK);
            MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[1]);
            when(userProfileService.uploadAvatar(file)).thenReturn(OK);

            userProfileController.getProfile();
            userProfileController.updateProfile(new ProfileUpdateDTO());
            userProfileController.uploadAvatar(file);

            verify(userProfileService).getProfile();
            verify(userProfileService).updateProfile(any(ProfileUpdateDTO.class));
            verify(userProfileService).uploadAvatar(file);
        }
    }

    @Nested
    @DisplayName("UserStatisticsController 用户统计")
    class Statistics {
        @Test
        @DisplayName("统计接口委托 service")
        void testDelegate() {
            when(userStatisticsService.getUserStatistics()).thenReturn(OK);
            userStatisticsController.getStatistics();
            verify(userStatisticsService).getUserStatistics();
        }
    }

    @Nested
    @DisplayName("TagSubscribeController 标签订阅")
    class Tag {
        @Test
        @DisplayName("发现/已关注/关注/取关/标签详情均委托 service")
        void testDelegate() {
            when(tagSubscribeService.discover("hottest", null, 1, 20)).thenReturn(OK);
            when(tagSubscribeService.getFollowed()).thenReturn(OK);
            when(tagSubscribeService.follow(7)).thenReturn(OK);
            when(tagSubscribeService.unfollow(7)).thenReturn(OK);
            when(tagSubscribeService.tagDetail("Java")).thenReturn(OK);

            tagSubscribeController.discover("hottest", null, 1, 20);
            tagSubscribeController.getFollowed();
            tagSubscribeController.follow(7);
            tagSubscribeController.unfollow(7);
            tagSubscribeController.tagDetail("Java");

            verify(tagSubscribeService).discover("hottest", null, 1, 20);
            verify(tagSubscribeService).getFollowed();
            verify(tagSubscribeService).follow(7);
            verify(tagSubscribeService).unfollow(7);
            verify(tagSubscribeService).tagDetail(eq("Java"));
        }
    }
}