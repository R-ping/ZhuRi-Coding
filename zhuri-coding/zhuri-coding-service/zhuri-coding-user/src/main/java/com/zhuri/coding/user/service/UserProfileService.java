package com.zhuri.coding.user.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dto.ProfileUpdateDTO;
import org.springframework.web.multipart.MultipartFile;

public interface UserProfileService {

    ResponseResult getProfile();

    ResponseResult updateProfile(ProfileUpdateDTO dto);

    ResponseResult uploadAvatar(MultipartFile file);
}