package com.zhuri.coding.user.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.user.dto.PasswordUpdateDTO;
import com.zhuri.coding.model.user.dto.PrivacyMessageDTO;

public interface AccountService {
    ResponseResult getBindings();
    ResponseResult updatePassword(PasswordUpdateDTO dto);
    ResponseResult deleteAccount();
    ResponseResult updatePrivacyMessage(PrivacyMessageDTO dto);
}