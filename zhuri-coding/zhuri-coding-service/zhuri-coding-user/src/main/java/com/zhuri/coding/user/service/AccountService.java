package com.heima.user.service;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dto.PasswordUpdateDTO;
import com.heima.model.user.dto.PrivacyMessageDTO;

public interface AccountService {
    ResponseResult getBindings();
    ResponseResult updatePassword(PasswordUpdateDTO dto);
    ResponseResult deleteAccount();
    ResponseResult updatePrivacyMessage(PrivacyMessageDTO dto);
}