package com.heima.user.service;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dto.ProfileUpdateDTO;
import org.springframework.web.multipart.MultipartFile;

public interface UserProfileService {

    ResponseResult getProfile();

    ResponseResult updateProfile(ProfileUpdateDTO dto);

    ResponseResult uploadAvatar(MultipartFile file);
}