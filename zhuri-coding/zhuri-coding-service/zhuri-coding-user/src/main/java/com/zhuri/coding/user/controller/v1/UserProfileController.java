package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dto.ProfileUpdateDTO;
import com.heima.user.service.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/user")
@Slf4j
public class UserProfileController {

    @Autowired
    private UserProfileService userProfileService;

    @GetMapping("/profile")
    public ResponseResult getProfile() {
        return userProfileService.getProfile();
    }

    @PutMapping("/profile")
    public ResponseResult updateProfile(@RequestBody ProfileUpdateDTO dto) {
        return userProfileService.updateProfile(dto);
    }

    @PostMapping("/avatar")
    public ResponseResult uploadAvatar(@RequestParam("file") MultipartFile file) {
        return userProfileService.uploadAvatar(file);
    }
}